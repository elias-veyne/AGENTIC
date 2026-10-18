// JNI bridge to the vendored llama.cpp runtime.
//
// Scope note: this is deliberately a *measurement* harness as much as an inference
// path. The PRD's performance targets (sub-800 ms time-to-first-token, 18 tok/s
// decode) cannot be checked from CI or from a desktop, because they depend entirely
// on the phone's CPU. So nativeBenchmark reports the numbers that matter on real
// hardware - load time, time to first token, and sustained decode rate - so the
// first thing to do with a build is run one and find out which of those holds.
//
// Threading: a llama_context is not safe for concurrent decode. Every entry point
// that touches a context takes the registry mutex. Callers still run this off the
// main thread: loading a model takes seconds and generation takes hundreds of
// milliseconds per token, either of which would trip an ANR.
//
// Memory: handles are raw pointers stored in a jlong. A 0 handle is the only
// "invalid" value and is rejected everywhere, so a stale Kotlin-side handle fails
// with a clear message rather than dereferencing freed memory.

#include <jni.h>

#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    uint32_t n_ctx = 0;
};

std::mutex g_mutex;
std::mutex g_backend_mutex;
bool g_backend_ready = false;

// Owns a Session so an exception during load cannot leak a half-built model.
struct SessionDeleter {
    void operator()(Session * s) const {
        if (!s) return;
        if (s->ctx) llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
        delete s;
    }
};
using SessionPtr = std::unique_ptr<Session, SessionDeleter>;

void ensure_backend() {
    std::lock_guard<std::mutex> guard(g_backend_mutex);
    if (g_backend_ready) return;
    llama_backend_init();
    g_backend_ready = true;
}

Session * resolve(jlong handle) {
    if (handle == 0) return nullptr;
    return reinterpret_cast<Session *>(handle);
}

// llama_tokenize returns a negative value on failure, whose magnitude is the number
// of tokens the buffer would have needed. Growing and retrying is the only way to
// size this correctly without a tokenizer pass, which is itself expensive.
int32_t tokenize(const llama_vocab * vocab, const std::string & text, std::vector<llama_token> & out) {
    int32_t needed = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (needed <= 0) return needed;

    out.resize((size_t) needed);
    int32_t written = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), needed, true, true);
    if (written < 0) {
        // Needed more than the estimate; give it a generous buffer and try once more.
        out.resize(out.size() * 2);
        written = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), (int32_t) out.size(), true, true);
        if (written < 0) return written;
    }
    out.resize((size_t) written);
    return written;
}

// One-shot: build a sampler chain and run greedy-or-sampled generation to EOG.
std::string generate(
    Session * session,
    const std::string & prompt,
    int32_t max_tokens,
    float temp,
    int32_t top_k,
    float top_p,
    long * ttft_ms,
    long * decode_ms,
    int32_t * token_count
) {
    const llama_vocab * vocab = session->vocab;
    llama_context * ctx = session->ctx;

    // Fresh context each request. Carrying KV across requests would let one prompt
    // silently influence the next, which is correct for a chat session and wrong
    // for a one-shot router decision.
    llama_memory_clear(llama_get_memory(ctx), true);

    std::vector<llama_token> tokens;
    if (tokenize(vocab, prompt, tokens) <= 0) return std::string();

    const int32_t room = (int32_t) session->n_ctx - max_tokens - 1;
    if (room <= 0) return std::string();
    if ((int32_t) tokens.size() > room) tokens.resize((size_t) room);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(ctx, batch) != 0) return std::string();

    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(chain_params);
    if (!chain) return std::string();

    // Order matters: top-k and top-p narrow the distribution before temperature
    // scales it. Adding temperature first would resample from the unfiltered set.
    if (top_k > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    if (temp <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
    }

    std::string out;
    std::vector<char> piece(256);
    const auto start = std::chrono::steady_clock::now();
    bool first = true;
    int32_t generated = 0;

    for (int32_t i = 0; i < max_tokens; ++i) {
        llama_token token = llama_sampler_sample(chain, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        if (first && ttft_ms) {
            *ttft_ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start).count();
            first = false;
        }

        int32_t len = llama_token_to_piece(vocab, token, piece.data(), (int32_t) piece.size(), 0, false);
        if (len < 0) {
            // Piece did not fit; grow and retry rather than dropping text.
            piece.resize((size_t) (-len) + 16);
            len = llama_token_to_piece(vocab, token, piece.data(), (int32_t) piece.size(), 0, false);
        }
        if (len > 0) out.append(piece.data(), (size_t) len);

        llama_sampler_accept(chain, token);
        generated++;

        llama_token next = token;
        llama_batch gen = llama_batch_get_one(&next, 1);
        if (llama_decode(ctx, gen) != 0) break;
    }

    const auto end = std::chrono::steady_clock::now();
    if (decode_ms) {
        *decode_ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    }
    if (token_count) *token_count = generated;
    if (ttft_ms && first) *ttft_ms = -1;  // never produced a token

    llama_sampler_free(chain);
    return out;
}

void throw_java(JNIEnv * env, const char * cls, const std::string & msg) {
    jclass c = env->FindClass(cls);
    if (c) env->ThrowNew(c, msg.c_str());
}

std::string to_string(JNIEnv * env, jstring s) {
    if (!s) return std::string();
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_jarves_mh_model_llama_LlamaNative_nativeBackendInfo(JNIEnv * env, jclass) {
    ensure_backend();
    const char * info = llama_print_system_info();
    return env->NewStringUTF(info ? info : "unknown");
}

JNIEXPORT jlong JNICALL
Java_com_jarves_mh_model_llama_LlamaNative_nativeLoadModel(
    JNIEnv * env, jclass, jstring path, jint n_ctx, jint n_threads, jint n_gpu_layers) {
    ensure_backend();
    std::lock_guard<std::mutex> guard(g_mutex);

    std::string model_path = to_string(env, path);
    if (model_path.empty()) {
        throw_java(env, "java/lang/IllegalArgumentException", "model path is empty");
        return 0;
    }
    if (n_ctx <= 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "n_ctx must be positive");
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    // Off by default on a CPU-only device: any positive value asks for GPU offload
    // that does not exist here and llama.cpp falls back with a backend warning.
    mparams.n_gpu_layers = n_gpu_layers;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) {
        throw_java(env, "java/lang/IllegalStateException",
                   "llama_model_load_from_file failed for " + model_path);
        return 0;
    }

    SessionPtr owned(new Session());
    owned->model = model;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    owned->ctx = llama_init_from_model(model, cparams);
    if (!owned->ctx) {
        // SessionDeleter frees the model here, so the load failure cannot leak it.
        throw_java(env, "java/lang/IllegalStateException", "llama_init_from_model failed");
        return 0;
    }

    owned->vocab = llama_model_get_vocab(model);
    owned->n_ctx = (uint32_t) n_ctx;

    Session * raw = owned.release();
    return reinterpret_cast<jlong> (raw);
}

JNIEXPORT void JNICALL
Java_com_jarves_mh_model_llama_LlamaNative_nativeFreeModel(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_mutex);
    Session * session = resolve(handle);
    if (!session) return;
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

JNIEXPORT jstring JNICALL
Java_com_jarves_mh_model_llama_LlamaNative_nativeModelDescription(JNIEnv * env, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_mutex);
    Session * session = resolve(handle);
    if (!session) return env->NewStringUTF("");

    std::vector<char> buf(256);
    int32_t n = llama_model_desc(session->model, buf.data(), buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize((size_t) n + 16);
        llama_model_desc(session->model, buf.data(), buf.size());
    }
    return env->NewStringUTF(std::string(buf.data()).c_str());
}

JNIEXPORT jint JNICALL
Java_com_jarves_mh_model_llama_LlamaNative_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> guard(g_mutex);
    Session * session = resolve(handle);
    return session ? (jint) session->n_ctx : 0;
}

JNIEXPORT jstring JNICALL
Java_com_jarves_mh_model_llama_LlamaNative_nativeGenerate(
    JNIEnv * env, jclass, jlong handle, jstring prompt, jint max_tokens,
    jfloat temp, jint top_k, jfloat top_p) {
    std::lock_guard<std::mutex> guard(g_mutex);
    Session * session = resolve(handle);
    if (!session) {
        throw_java(env, "java/lang/IllegalStateException", "model handle is not loaded");
        return nullptr;
    }

    std::string out = generate(session, to_string(env, prompt), max_tokens, temp, top_k, top_p,
                               nullptr, nullptr, nullptr);
    return env->NewStringUTF(out.c_str());
}

// Returns { load-independent timings }. Index order matches BenchmarkResult in Kotlin:
//   0 timeToFirstTokenMs  (-1 if no token was produced)
//   1 totalDecodeMs
//   2 tokensGenerated
//   3 decodeTokensPerSecond
//   4 promptTokens
JNIEXPORT jfloatArray JNICALL
Java_com_jarves_mh_model_llama_LlamaNative_nativeBenchmark(
    JNIEnv * env, jclass, jlong handle, jstring prompt, jint max_tokens,
    jfloat temp, jint top_k, jfloat top_p) {
    std::lock_guard<std::mutex> guard(g_mutex);
    Session * session = resolve(handle);
    if (!session) {
        throw_java(env, "java/lang/IllegalStateException", "model handle is not loaded");
        return nullptr;
    }

    std::string text = to_string(env, prompt);
    std::vector<llama_token> tokens;
    int32_t prompt_tokens = tokenize(session->vocab, text, tokens);

    long ttft_ms = -1;
    long decode_ms = 0;
    int32_t generated = 0;
    generate(session, text, max_tokens, temp, top_k, top_p, &ttft_ms, &decode_ms, &generated);

    jfloat out[5];
    out[0] = (jfloat) ttft_ms;
    out[1] = (jfloat) decode_ms;
    out[2] = (jfloat) generated;
    // Guard the divide: a run that produced no tokens must report 0 tok/s, not a
    // NaN or an infinity that would poison an average taken over several runs.
    out[3] = (decode_ms > 0 && generated > 0) ? (jfloat) generated * 1000.0f / (jfloat) decode_ms : 0.0f;
    out[4] = (jfloat) (prompt_tokens > 0 ? prompt_tokens : 0);

    jfloatArray arr = env->NewFloatArray(5);
    if (arr) env->SetFloatArrayRegion(arr, 0, 5, out);
    return arr;
}

}  // extern "C"
