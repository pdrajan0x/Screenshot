// Minimal JNI bridge to llama.cpp for on-device screenshot summaries.
// Only the core C API (llama.h) is used, so it stays stable across llama.cpp releases.
// Text crosses the boundary as UTF-8 byte arrays: JNI's "modified UTF-8" mangles emoji.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <string>
#include <vector>

#include <unistd.h>

#include "ggml-backend.h"
#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "DotLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

namespace {

struct Engine {
    // Set when the model was opened from a file descriptor (a file picked from shared storage).
    FILE *file = nullptr;
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 0;
    std::atomic<bool> cancel{false};
    std::string last_error;
    // Tokens held in the KV cache (sequence 0, from position 0). A new prompt that starts the same
    // way (the system prompt is identical for every screenshot) only decodes what differs.
    std::vector<llama_token> cached;
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
}

std::string to_string(JNIEnv *env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int n = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    std::vector<llama_token> tokens(static_cast<size_t>(std::max(n, 0)));
    if (n > 0) {
        llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), n, true, true);
    }
    return tokens;
}

void reset_cache(Engine *engine) {
    llama_memory_clear(llama_get_memory(engine->ctx), true);
    engine->cached.clear();
}

// Brings the KV cache to exactly [tokens], reusing the longest prefix already there. Afterwards the
// logits of the last token are available. Returns false (with last_error set) on failure.
bool feed(Engine *engine, const std::vector<llama_token> &tokens) {
    size_t common = 0;
    while (common < engine->cached.size() && common < tokens.size() && engine->cached[common] == tokens[common]) common++;
    // The last token is always decoded again so its logits are fresh.
    if (common == tokens.size()) common = tokens.size() - 1;
    if (!llama_memory_seq_rm(llama_get_memory(engine->ctx), 0, static_cast<llama_pos>(common), -1)) {
        reset_cache(engine);
        common = 0;
    }
    engine->cached.resize(common);
    for (size_t i = common; i < tokens.size(); i += static_cast<size_t>(engine->n_batch)) {
        const int n = static_cast<int>(std::min(tokens.size() - i, static_cast<size_t>(engine->n_batch)));
        std::vector<llama_token> chunk(tokens.begin() + static_cast<long>(i), tokens.begin() + static_cast<long>(i) + n);
        if (llama_decode(engine->ctx, llama_batch_get_one(chunk.data(), n)) != 0) {
            reset_cache(engine);
            engine->last_error = "prompt decode failed";
            return false;
        }
        engine->cached.insert(engine->cached.end(), chunk.begin(), chunk.end());
        if (engine->cancel) {
            engine->last_error = "cancelled";
            return false;
        }
    }
    return true;
}

std::string piece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, token, big.data(), -n, 0, false);
        return n > 0 ? big.substr(0, static_cast<size_t>(n)) : std::string();
    }
    return std::string(buf, static_cast<size_t>(n));
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeInit(JNIEnv *env, jclass, jstring backend_dir) {
    llama_log_set(log_callback, nullptr);
    if (backend_dir != nullptr) {
        const char *dir = env->GetStringUTFChars(backend_dir, nullptr);
        // CPU backend variants (armv8.0 … armv9.2) live next to this library; the best one for
        // this phone's CPU is picked at runtime.
        ggml_backend_load_all_from_path(dir);
        env->ReleaseStringUTFChars(backend_dir, dir);
    }
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

// Loads from [jpath], or from [fd] when it is >= 0. A file another app made in shared storage can
// only be read through the descriptor the system file picker hands out: reopening it by path
// (even /proc/self/fd/N) is refused, so llama.cpp reads from a duplicate of that descriptor.
JNIEXPORT jlong JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint fd, jint n_ctx, jint n_threads) {
    llama_model_params mparams = llama_model_default_params();
    llama_model *model = nullptr;
    FILE *file = nullptr;
    if (fd >= 0) {
        const int own = dup(fd);
        file = own >= 0 ? fdopen(own, "rb") : nullptr;
        if (file == nullptr) {
            if (own >= 0) close(own);
            LOGE("could not open the model file descriptor");
            return 0;
        }
        model = llama_model_load_from_file_ptr(file, mparams);
    } else {
        const char *path = env->GetStringUTFChars(jpath, nullptr);
        model = llama_model_load_from_file(path, mparams);
        env->ReleaseStringUTFChars(jpath, path);
    }
    if (model == nullptr) {
        LOGE("model load failed");
        if (file != nullptr) fclose(file);
        return 0;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("context init failed");
        llama_model_free(model);
        if (file != nullptr) fclose(file);
        return 0;
    }
    auto *engine = new Engine();
    engine->file = file;
    engine->model = model;
    engine->ctx = ctx;
    engine->vocab = llama_model_get_vocab(model);
    engine->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    engine->n_batch = 512;
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jint JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeCountTokens(JNIEnv *env, jclass, jlong handle, jbyteArray text) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    return static_cast<jint>(tokenize(engine->vocab, to_string(env, text)).size());
}

// Returns the generated UTF-8 bytes, or null on failure (see nativeLastError).
JNIEXPORT jbyteArray JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeGenerate(JNIEnv *env, jclass, jlong handle, jbyteArray jprompt,
                                                     jbyteArray jgrammar, jint max_tokens, jfloat temperature,
                                                     jint seed) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    engine->cancel = false;
    engine->last_error.clear();

    const std::string prompt = to_string(env, jprompt);
    const std::string grammar = to_string(env, jgrammar);
    std::vector<llama_token> tokens = tokenize(engine->vocab, prompt);
    if (tokens.empty()) {
        engine->last_error = "empty prompt";
        return nullptr;
    }
    if (static_cast<int>(tokens.size()) + max_tokens > engine->n_ctx) {
        engine->last_error = "prompt too long: " + std::to_string(tokens.size()) + " tokens";
        return nullptr;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(engine->vocab, grammar.c_str(), "root");
        if (g == nullptr) {
            llama_sampler_free(smpl);
            engine->last_error = "invalid grammar";
            return nullptr;
        }
        llama_sampler_chain_add(smpl, g);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(engine->vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    if (!feed(engine, tokens)) {
        llama_sampler_free(smpl);
        return nullptr;
    }

    std::string out;
    for (int i = 0; i < max_tokens; i++) {
        if (engine->cancel) {
            engine->last_error = "cancelled";
            break;
        }
        llama_token token = llama_sampler_sample(smpl, engine->ctx, -1);
        if (llama_vocab_is_eog(engine->vocab, token)) break;
        out += piece(engine->vocab, token);
        if (llama_decode(engine->ctx, llama_batch_get_one(&token, 1)) != 0) {
            reset_cache(engine);
            engine->last_error = "decode failed";
            break;
        }
        engine->cached.push_back(token);
    }
    llama_sampler_free(smpl);
    if (engine->last_error == "cancelled") return nullptr;
    return to_bytes(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeLastError(JNIEnv *env, jclass, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    return env->NewStringUTF(engine->last_error.c_str());
}

JNIEXPORT void JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeCancel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Engine *>(handle)->cancel = true;
}

JNIEXPORT void JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return;
    llama_free(engine->ctx);
    llama_model_free(engine->model);
    if (engine->file != nullptr) fclose(engine->file);
    delete engine;
}

}  // extern "C"
