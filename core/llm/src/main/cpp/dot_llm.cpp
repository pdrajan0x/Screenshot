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
#include "mtmd.h"
#include "mtmd-helper.h"

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
    // Image understanding (a vision model's projector), when loaded.
    mtmd_context *vision = nullptr;
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

std::string piece(const llama_vocab *vocab, llama_token token);

// Greedy (or sampled) decoding with a light repetition penalty, optionally constrained by a GBNF
// grammar. Null (with last_error set) for an invalid grammar.
llama_sampler *make_sampler(Engine *engine, const std::string &grammar, float temperature, int seed) {
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
    return smpl;
}

// Generates up to [max_tokens] after what's in the KV cache. Generated tokens are recorded in
// engine->cached when [track] (text prompts), so the next prompt can reuse them.
std::string sample_loop(Engine *engine, llama_sampler *smpl, int max_tokens, bool track) {
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
        if (track) engine->cached.push_back(token);
    }
    return out;
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

    llama_sampler *smpl = make_sampler(engine, grammar, temperature, seed);
    if (smpl == nullptr) return nullptr;
    if (!feed(engine, tokens)) {
        llama_sampler_free(smpl);
        return nullptr;
    }
    const std::string out = sample_loop(engine, smpl, max_tokens, true);
    llama_sampler_free(smpl);
    if (engine->last_error == "cancelled") return nullptr;
    return to_bytes(env, out);
}

// Loads a vision model's projector (mmproj) for the loaded text model, so nativeDescribe can see
// images. [max_image_tokens] > 0 caps how many tokens an image becomes (where the model allows).
JNIEXPORT jboolean JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeLoadVision(JNIEnv *env, jclass, jlong handle, jstring jpath, jint n_threads,
                                                       jint max_image_tokens) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine->vision != nullptr) return JNI_TRUE;
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.print_timings = false;
    params.warmup = false;
    params.n_threads = n_threads;
    if (max_image_tokens > 0) params.image_max_tokens = max_image_tokens;
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    engine->vision = mtmd_init_from_file(path, engine->model, params);
    env->ReleaseStringUTFChars(jpath, path);
    if (engine->vision == nullptr || !mtmd_support_vision(engine->vision)) {
        LOGE("vision projector load failed");
        if (engine->vision != nullptr) mtmd_free(engine->vision);
        engine->vision = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Answers [jinstruction] about an RGB image ([w] x [h], 3 bytes per pixel) using the model's own
// chat template. Returns the UTF-8 answer, or null on failure (see nativeLastError).
JNIEXPORT jbyteArray JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeDescribe(JNIEnv *env, jclass, jlong handle, jbyteArray jrgb, jint w, jint h,
                                                     jbyteArray jinstruction, jint max_tokens) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    engine->cancel = false;
    engine->last_error.clear();
    if (engine->vision == nullptr) {
        engine->last_error = "no vision projector";
        return nullptr;
    }
    const jsize n_bytes = env->GetArrayLength(jrgb);
    if (w <= 0 || h <= 0 || n_bytes != w * h * 3) {
        engine->last_error = "bad image size";
        return nullptr;
    }
    std::vector<unsigned char> rgb(static_cast<size_t>(n_bytes));
    env->GetByteArrayRegion(jrgb, 0, n_bytes, reinterpret_cast<jbyte *>(rgb.data()));

    // The image goes where the media marker is; the model's chat template wraps the turn.
    const std::string content = std::string(mtmd_default_marker()) + to_string(env, jinstruction);
    llama_chat_message message{"user", content.c_str()};
    const char *tmpl = llama_model_chat_template(engine->model, nullptr);
    std::vector<char> buf(content.size() * 2 + 512);
    int32_t n = llama_chat_apply_template(tmpl, &message, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(n));
        n = llama_chat_apply_template(tmpl, &message, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    const std::string prompt = n > 0 ? std::string(buf.data(), static_cast<size_t>(n))
                                     : "<|im_start|>user\n" + content + "<|im_end|>\n<|im_start|>assistant\n";

    mtmd_bitmap *bitmap = mtmd_bitmap_init(static_cast<uint32_t>(w), static_cast<uint32_t>(h), rgb.data());
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    const mtmd_bitmap *bitmaps[] = {bitmap};
    jbyteArray result = nullptr;
    if (mtmd_tokenize(engine->vision, chunks, &text, bitmaps, 1) != 0) {
        engine->last_error = "image tokenize failed";
    } else if (static_cast<int>(mtmd_helper_get_n_tokens(chunks)) + max_tokens > engine->n_ctx) {
        engine->last_error = "image prompt too long";
    } else {
        // Images aren't tracked as tokens: start from an empty cache, and leave it marked empty.
        reset_cache(engine);
        llama_pos n_past = 0;
        if (mtmd_helper_eval_chunks(engine->vision, engine->ctx, chunks, 0, 0, engine->n_batch, true, &n_past) != 0) {
            engine->last_error = "image decode failed";
        } else {
            llama_sampler *smpl = make_sampler(engine, "", 0.0f, 0);
            const std::string out = sample_loop(engine, smpl, max_tokens, false);
            llama_sampler_free(smpl);
            if (engine->last_error != "cancelled") result = to_bytes(env, out);
        }
        reset_cache(engine);
    }
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    return result;
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
    if (engine->vision != nullptr) mtmd_free(engine->vision);
    llama_free(engine->ctx);
    llama_model_free(engine->model);
    if (engine->file != nullptr) fclose(engine->file);
    delete engine;
}

}  // extern "C"
