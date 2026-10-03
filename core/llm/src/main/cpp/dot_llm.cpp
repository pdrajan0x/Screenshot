// Minimal JNI bridge to llama.cpp + libmtmd: the on-device vision model that describes photos and
// summarises screenshots. Only the C APIs (llama.h, mtmd.h) are used, so it stays stable across
// llama.cpp releases. Text crosses the boundary as UTF-8 byte arrays: JNI's "modified UTF-8"
// mangles emoji.

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

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
    llama_model *model = nullptr;
    // Image understanding (a vision model's projector), when loaded.
    mtmd_context *vision = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 0;
    std::atomic<bool> cancel{false};
    std::string last_error;
};

// Recent warnings and errors from llama.cpp and the vision projector, so the app's own log (what
// the user can see and share) says why loading failed, not just that it did.
std::mutex log_mutex;
std::string recent_log;

void remember(const char *text) {
    std::lock_guard<std::mutex> lock(log_mutex);
    recent_log += text;
    if (!recent_log.empty() && recent_log.back() != '\n') recent_log += '\n';
    if (recent_log.size() > 4000) recent_log.erase(0, recent_log.size() - 4000);
}

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) remember(text);
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

void reset_cache(Engine *engine) {
    llama_memory_clear(llama_get_memory(engine->ctx), true);
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

// Greedy decoding with a light repetition penalty, constrained by a GBNF grammar when one is given.
// Null (with last_error set) for an invalid grammar.
llama_sampler *make_sampler(Engine *engine, const std::string &grammar) {
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
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    return smpl;
}

// Generates up to [max_tokens] after what's in the KV cache.
std::string sample_loop(Engine *engine, llama_sampler *smpl, int max_tokens) {
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
            engine->last_error = "decode failed";
            break;
        }
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeInit(JNIEnv *env, jclass, jstring backend_dir) {
    llama_log_set(log_callback, nullptr);
    mtmd_helper_log_set(log_callback, nullptr);
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

// Loads the text half of the model from [jpath]; [n_ctx] tokens of context. If the usual way fails,
// tries safer ones: reading the file into memory instead of mapping it, then without the repacked
// (KleidiAI / ARM) weight layouts.
JNIEXPORT jlong JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    llama_model_params mparams = llama_model_default_params();
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model *model = llama_model_load_from_file(path, mparams);
    if (model == nullptr) {
        remember("dotllm: load failed; retrying without memory-mapping");
        mparams.load_mode = LLAMA_LOAD_MODE_NONE;
        model = llama_model_load_from_file(path, mparams);
    }
    if (model == nullptr) {
        remember("dotllm: load failed; retrying without repacked weights");
        mparams.use_extra_bufts = false;
        model = llama_model_load_from_file(path, mparams);
    }
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        LOGE("model load failed");
        remember("dotllm: model load failed");
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
        remember("dotllm: context init failed");
        llama_model_free(model);
        return 0;
    }
    auto *engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->vocab = llama_model_get_vocab(model);
    engine->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    engine->n_batch = 512;
    return reinterpret_cast<jlong>(engine);
}

// Loads a vision model's projector (mmproj) for the loaded text model, so nativeDescribe can see
// images. [max_image_tokens] > 0 caps how many tokens an image becomes (where the model allows).
JNIEXPORT jboolean JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeLoadVision(JNIEnv *env, jclass, jlong handle, jstring jpath, jint n_threads,
                                                       jint max_image_tokens) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return JNI_FALSE;
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
        remember("dotllm: vision projector load failed");
        if (engine->vision != nullptr) mtmd_free(engine->vision);
        engine->vision = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Answers [jinstruction] about an RGB image ([w] x [h], 3 bytes per pixel) using the model's own
// chat template, optionally constrained by a GBNF [jgrammar]. Returns the UTF-8 answer, or null on
// failure (see nativeLastError).
JNIEXPORT jbyteArray JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeDescribe(JNIEnv *env, jclass, jlong handle, jbyteArray jrgb, jint w, jint h,
                                                     jbyteArray jinstruction, jbyteArray jgrammar, jint max_tokens) {
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
        // Every image starts from an empty cache.
        reset_cache(engine);
        llama_pos n_past = 0;
        if (mtmd_helper_eval_chunks(engine->vision, engine->ctx, chunks, 0, 0, engine->n_batch, true, &n_past) != 0) {
            engine->last_error = "image decode failed";
        } else {
            llama_sampler *smpl = make_sampler(engine, to_string(env, jgrammar));
            if (smpl != nullptr) {
                const std::string out = sample_loop(engine, smpl, max_tokens);
                llama_sampler_free(smpl);
                if (engine->last_error != "cancelled") result = to_bytes(env, out);
            }
        }
        reset_cache(engine);
    }
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    return result;
}

// Returns and clears the recent warnings and errors (see remember).
JNIEXPORT jstring JNICALL
Java_com_pdrajan_dot_llm_LlamaNative_nativeTakeLog(JNIEnv *env, jclass) {
    std::string out;
    {
        std::lock_guard<std::mutex> lock(log_mutex);
        out.swap(recent_log);
    }
    // NewStringUTF wants (modified) UTF-8; keep it to plain ASCII so odd bytes can't trip it.
    for (char &ch : out) {
        if (static_cast<unsigned char>(ch) >= 0x80) ch = '?';
    }
    return env->NewStringUTF(out.c_str());
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
    delete engine;
}

}  // extern "C"
