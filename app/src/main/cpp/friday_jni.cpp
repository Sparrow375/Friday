#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cmath>
#include "whisper.h"
#include "llama.h"

#define LOG_TAG "FridayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

    jfloat *samples = env->GetFloatArrayElements(audio_samples, nullptr);
    jsize len = env->GetArrayLength(audio_samples);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 1;
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
    llama_model *model = llama_load_model_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);
    
    if (model == nullptr) {
        LOGE("Failed to load Llama model");
        return 0;
    }
    
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
            llama_memory_t mem = llama_get_memory(state->ctx);
            if (mem != nullptr) {
                llama_memory_clear(mem, true);
            }
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

    const char *prompt_raw = env->GetStringUTFChars(prompt_str, nullptr);
    std::string prompt(prompt_raw);
    env->ReleaseStringUTFChars(prompt_str, prompt_raw);

    LOGI("generateLlama started. Prompt: '%s'", prompt.c_str());

    // Retrieve vocabulary from model
    const struct llama_vocab * vocab = llama_model_get_vocab(state->model);

    // Tokenize prompt
    // For llama.cpp, we calculate number of tokens first
    int n_tokens_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.length(), nullptr, 0, true, true);
    LOGI("Prompt length: %d. Estimated tokens required: %d", (int)prompt.length(), n_tokens_prompt);
    std::vector<llama_token> prompt_tokens(n_tokens_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.length(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("Error: Tokenization failed");
    }
    LOGI("Tokenization complete. Actual prompt tokens count: %d", (int)prompt_tokens.size());

    // Append new prompt tokens to history
    size_t old_history_size = state->history_tokens.size();
    state->history_tokens.insert(state->history_tokens.end(), prompt_tokens.begin(), prompt_tokens.end());
    LOGI("History size updated from %d to %d", (int)old_history_size, (int)state->history_tokens.size());

    // Simple decoder loop
    std::string response = "";
    int n_generated = 0;
    
    // Setup sampler
    struct llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    LOGI("Sampler initialized with temp=%.2f", temp);

    // Decode prompt first if history is new or decode everything sequentially
    // We decode in batches.
    size_t current_pos = old_history_size;
    size_t total_tokens = state->history_tokens.size();

    // Decode loop
    while (n_generated < max_tokens) {
        // Prepare batch
        size_t n_eval = total_tokens - current_pos;
        if (n_eval == 0) {
            LOGI("n_eval is 0, breaking loop");
            break;
        }

        // Clip batch size
        if (n_eval > 512) {
            n_eval = 512;
        }

        LOGI("Loop iteration %d: n_eval=%d, current_pos=%d, total_tokens=%d", 
             n_generated, (int)n_eval, (int)current_pos, (int)total_tokens);

        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        batch.n_tokens = n_eval;
        for (size_t i = 0; i < n_eval; ++i) {
            batch.token[i] = state->history_tokens[current_pos + i];
            batch.pos[i] = current_pos + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == n_eval - 1); // Only compute logits for the last token in batch
        }

        LOGI("Calling llama_decode for %d tokens...", (int)n_eval);
        int decode_res = llama_decode(state->ctx, batch);
        LOGI("llama_decode returned: %d", decode_res);

        if (decode_res != 0) {
            LOGE("Llama decode failed with status %d", decode_res);
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            return env->NewStringUTF("Error: Decode failed");
        }

        current_pos += n_eval;
        llama_batch_free(batch);

        // Sample next token
        LOGI("Sampling next token...");
        llama_token id = llama_sampler_sample(smpl, state->ctx, -1);
        LOGI("Sampled token: %d", id);
        
        // Add to history
        state->history_tokens.push_back(id);
        total_tokens++;

        // Check for EOS
        if (llama_vocab_is_eog(vocab, id)) {
            LOGI("Sampled EOG token (id=%d), finishing generation", id);
            break;
        }

        // Convert token to piece
        char buf[128];
        int n_chars = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            response.append(buf, n_chars);
            LOGI("Generated token piece: '%*s'", n_chars, buf);
        } else {
            LOGI("Generated empty token piece (n_chars=%d)", n_chars);
        }

        n_generated++;
    }

    LOGI("generateLlama finished. Generated tokens count: %d. Response: '%s'", n_generated, response.c_str());
    llama_sampler_free(smpl);
    return env->NewStringUTF(response.c_str());
}
