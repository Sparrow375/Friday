#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cmath>
#include <sched.h>
#include <unistd.h>
#include "whisper.h"
#include "llama.h"

#define LOG_TAG "FridayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper to set CPU core affinity to high-performance cores (4-7)
static void set_thread_affinity() {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(4, &cpuset);
    CPU_SET(5, &cpuset);
    CPU_SET(6, &cpuset);
    CPU_SET(7, &cpuset);
    pid_t tid = gettid();
    if (sched_setaffinity(tid, sizeof(cpu_set_t), &cpuset) != 0) {
        LOGE("Failed to set thread affinity for thread %d", tid);
    } else {
        LOGI("Successfully bound thread %d to performance CPU cores 4-7", tid);
    }
}

// ==========================================
// Whisper.cpp JNI Wrapper
// ==========================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_friday_assistant_core_native_WhisperEngine_initWhisper(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing Whisper with model: %s", path);
    struct whisper_context_params params = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    if (!ctx) {
        LOGE("Failed to initialize Whisper context");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_friday_assistant_core_native_WhisperEngine_freeWhisper(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (ctx != nullptr) {
        LOGI("Freeing Whisper context");
        whisper_free(ctx);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_friday_assistant_core_native_WhisperEngine_transcribeWhisper(JNIEnv *env, jobject thiz, jlong ctx_ptr, jfloatArray audio_samples) {
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (ctx == nullptr) {
        LOGE("Whisper context is null");
        return env->NewStringUTF("");
    }

    // Bind Whisper transcription execution to high-performance cores
    set_thread_affinity();

    jfloat *samples = env->GetFloatArrayElements(audio_samples, nullptr);
    jsize len = env->GetArrayLength(audio_samples);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4; // Multi-threaded Whisper decoding
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.language = "en";
    params.translate = false;

    LOGI("Starting Whisper transcription on %d samples", len);
    int ret = whisper_full(ctx, params, samples, len);
    env->ReleaseFloatArrayElements(audio_samples, samples, JNI_ABORT);

    if (ret != 0) {
        LOGE("Whisper transcription failed with code: %d", ret);
        return env->NewStringUTF("");
    }

    std::string result = "";
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        result += text;
    }
    LOGI("Transcription finished: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// ==========================================
// Llama.cpp JNI Wrapper
// ==========================================

struct LlamaState {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::vector<llama_token> history_tokens;
};

static void llama_log_callback_android(enum ggml_log_level level, const char * text, void * user_data) {
    (void)user_data;
    android_LogPriority priority = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  priority = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_INFO:  priority = ANDROID_LOG_INFO; break;
        default:                   priority = ANDROID_LOG_DEBUG; break;
    }
    __android_log_write(priority, "llama.cpp", text);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_friday_assistant_core_native_LlamaEngine_initLlama(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing Llama backend and loading model: %s", path);
    
    // Set up Android logcat logging redirect
    llama_log_set(llama_log_callback_android, nullptr);
    
    // Initialize backend
    llama_backend_init();
    
    // Load model
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 99; // Offload all layers to Vulkan GPU if available
    llama_model *model = llama_load_model_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);
    
    if (model == nullptr) {
        LOGE("Failed to load Llama model");
        return 0;
    }
    
    // Bind current thread to performance cores BEFORE context creation
    // so that the internal llama.cpp threadpool inherits the CPU affinity
    set_thread_affinity();

    // Create context
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048; // Context size
    cparams.n_batch = 512;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;
    
    llama_context *ctx = llama_new_context_with_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("Failed to create Llama context");
        llama_free_model(model);
        return 0;
    }
    
    LlamaState *state = new LlamaState();
    state->model = model;
    state->ctx = ctx;
    
    LOGI("Llama model initialized successfully");
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_friday_assistant_core_native_LlamaEngine_freeLlama(JNIEnv *env, jobject thiz, jlong state_ptr) {
    LlamaState *state = reinterpret_cast<LlamaState *>(state_ptr);
    if (state != nullptr) {
        LOGI("Freeing Llama resources");
        if (state->ctx) {
            llama_free(state->ctx);
        }
        if (state->model) {
            llama_free_model(state->model);
        }
        delete state;
        llama_backend_free();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_friday_assistant_core_native_LlamaEngine_clearLlamaHistory(JNIEnv *env, jobject thiz, jlong state_ptr) {
    LlamaState *state = reinterpret_cast<LlamaState *>(state_ptr);
    if (state != nullptr) {
        state->history_tokens.clear();
        if (state->ctx) {
            llama_memory_t kv = llama_get_memory(state->ctx);
            llama_memory_clear(kv, true);
        }
        LOGI("Llama context history and KV cache cleared");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_friday_assistant_core_native_LlamaEngine_generateLlama(
        JNIEnv *env, jobject thiz, jlong state_ptr, jstring prompt_str, jint max_tokens, jfloat temp) {
    
    LlamaState *state = reinterpret_cast<LlamaState *>(state_ptr);
    if (state == nullptr || state->ctx == nullptr || state->model == nullptr) {
        LOGE("Llama state is null");
        return env->NewStringUTF("Error: Model state is null");
    }

    // Bind generation calling thread to high-performance cores
    set_thread_affinity();

    const char *prompt_raw = env->GetStringUTFChars(prompt_str, nullptr);
    std::string prompt(prompt_raw);
    env->ReleaseStringUTFChars(prompt_str, prompt_raw);

    LOGI("generateLlama started. Prompt length: %d", (int)prompt.length());

    // Retrieve vocabulary from model
    const struct llama_vocab * vocab = llama_model_get_vocab(state->model);

    // Tokenize prompt
    int n_tokens_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.length(), nullptr, 0, true, true);
    std::vector<llama_token> prompt_tokens(n_tokens_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.length(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("Error: Tokenization failed");
    }
    LOGI("Tokenization complete. Prompt tokens count: %d", (int)prompt_tokens.size());

    // Find longest common prefix between incoming prompt and existing cache
    size_t n_keep = 0;
    while (n_keep < state->history_tokens.size() &&
           n_keep < prompt_tokens.size() &&
           state->history_tokens[n_keep] == prompt_tokens[n_keep]) {
        n_keep++;
    }

    LOGI("KV cache comparison: n_keep = %d, history_size = %d", (int)n_keep, (int)state->history_tokens.size());

    // Truncate KV Cache to matched prefix length
    if (n_keep < state->history_tokens.size()) {
        LOGI("Cache mismatch. Truncating KV cache from position %d to end (-1)", (int)n_keep);
        llama_memory_t kv = llama_get_memory(state->ctx);
        llama_memory_seq_rm(kv, 0, n_keep, -1);
        state->history_tokens.resize(n_keep);
    }

    size_t n_new = prompt_tokens.size() - n_keep;
    
    // Fallback/Correction: If prefix matches completely (n_new == 0), re-evaluate the last token
    // to calculate its logits for sampling.
    if (n_new == 0 && n_keep > 0) {
        n_keep--;
        llama_memory_t kv = llama_get_memory(state->ctx);
        llama_memory_seq_rm(kv, 0, n_keep, -1);
        state->history_tokens.resize(n_keep);
        n_new = prompt_tokens.size() - n_keep;
    }

    // Append new suffix tokens to history
    if (n_new > 0) {
        state->history_tokens.insert(state->history_tokens.end(),
                                     prompt_tokens.begin() + n_keep,
                                     prompt_tokens.end());
    }

    // Setup sampler
    struct llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string response = "";
    int n_generated = 0;
    size_t current_pos = n_keep;
    size_t total_tokens = state->history_tokens.size();

    // Decode loop
    while (n_generated < max_tokens) {
        size_t n_eval = total_tokens - current_pos;
        if (n_eval == 0) {
            break;
        }

        if (n_eval > 512) {
            n_eval = 512;
        }

        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        batch.n_tokens = n_eval;
        for (size_t i = 0; i < n_eval; ++i) {
            batch.token[i] = state->history_tokens[current_pos + i];
            batch.pos[i] = current_pos + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == n_eval - 1); // Compute logits only for the last token in batch
        }

        int decode_res = llama_decode(state->ctx, batch);
        if (decode_res != 0) {
            LOGE("Llama decode failed with status %d", decode_res);
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            return env->NewStringUTF("Error: Decode failed");
        }

        current_pos += n_eval;
        llama_batch_free(batch);

        // Sample next token
        llama_token id = llama_sampler_sample(smpl, state->ctx, -1);
        
        state->history_tokens.push_back(id);
        total_tokens++;

        // Check for End of Generation
        if (llama_vocab_is_eog(vocab, id)) {
            LOGI("EOG token (id=%d) detected, generation stopped", id);
            break;
        }

        // Convert token to char piece
        char buf[128];
        int n_chars = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            response.append(buf, n_chars);
        }

        n_generated++;
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_friday_assistant_core_native_LlamaEngine_generateLlamaStream(
        JNIEnv *env, jobject thiz, jlong state_ptr, jstring prompt_str, jint max_tokens, jfloat temp, jobject callback) {
    
    LlamaState *state = reinterpret_cast<LlamaState *>(state_ptr);
    if (state == nullptr || state->ctx == nullptr || state->model == nullptr) {
        LOGE("Llama state is null");
        return env->NewStringUTF("Error: Model state is null");
    }

    set_thread_affinity();

    const char *prompt_raw = env->GetStringUTFChars(prompt_str, nullptr);
    std::string prompt(prompt_raw);
    env->ReleaseStringUTFChars(prompt_str, prompt_raw);

    LOGI("generateLlamaStream started. Prompt length: %d", (int)prompt.length());

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    if (onTokenMethod == nullptr) {
        LOGE("Could not find onToken method on callback object");
        return env->NewStringUTF("Error: Callback lookup failed");
    }

    const struct llama_vocab * vocab = llama_model_get_vocab(state->model);

    int n_tokens_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.length(), nullptr, 0, true, true);
    std::vector<llama_token> prompt_tokens(n_tokens_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.length(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("Error: Tokenization failed");
    }

    size_t n_keep = 0;
    while (n_keep < state->history_tokens.size() &&
           n_keep < prompt_tokens.size() &&
           state->history_tokens[n_keep] == prompt_tokens[n_keep]) {
        n_keep++;
    }

    if (n_keep < state->history_tokens.size()) {
        llama_memory_t kv = llama_get_memory(state->ctx);
        llama_memory_seq_rm(kv, 0, n_keep, -1);
        state->history_tokens.resize(n_keep);
    }

    size_t n_new = prompt_tokens.size() - n_keep;
    if (n_new == 0 && n_keep > 0) {
        n_keep--;
        llama_memory_t kv = llama_get_memory(state->ctx);
        llama_memory_seq_rm(kv, 0, n_keep, -1);
        state->history_tokens.resize(n_keep);
        n_new = prompt_tokens.size() - n_keep;
    }

    if (n_new > 0) {
        state->history_tokens.insert(state->history_tokens.end(),
                                     prompt_tokens.begin() + n_keep,
                                     prompt_tokens.end());
    }

    struct llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string response = "";
    int n_generated = 0;
    size_t current_pos = n_keep;
    size_t total_tokens = state->history_tokens.size();

    while (n_generated < max_tokens) {
        size_t n_eval = total_tokens - current_pos;
        if (n_eval == 0) {
            break;
        }

        if (n_eval > 512) {
            n_eval = 512;
        }

        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        batch.n_tokens = n_eval;
        for (size_t i = 0; i < n_eval; ++i) {
            batch.token[i] = state->history_tokens[current_pos + i];
            batch.pos[i] = current_pos + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == n_eval - 1);
        }

        int decode_res = llama_decode(state->ctx, batch);
        if (decode_res != 0) {
            LOGE("Llama decode failed with status %d", decode_res);
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            return env->NewStringUTF("Error: Decode failed");
        }

        current_pos += n_eval;
        llama_batch_free(batch);

        llama_token id = llama_sampler_sample(smpl, state->ctx, -1);
        
        state->history_tokens.push_back(id);
        total_tokens++;

        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        char buf[128];
        int n_chars = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            std::string piece(buf, n_chars);
            response.append(piece);
            
            jstring jpiece = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        n_generated++;
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(response.c_str());
}
