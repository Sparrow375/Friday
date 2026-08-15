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
    mparams.n_gpu_layers = 0;     // Vulkan is OFF in CMakeLists.txt; setting 99 caused silent CPU fallback overhead
    llama_model *model = llama_model_load_from_file(path, mparams);
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
    cparams.n_ctx = 1024;  // 1024 tokens of context for reliable multi-turn conversations
    cparams.n_batch = 512;
    cparams.n_threads = 4; // S24 performance cores
    cparams.n_threads_batch = 4;
    
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("Failed to create Llama context");
        llama_model_free(model);
        return 0;
    }
    
    LlamaState *state = new LlamaState();
    state->model = model;
    state->ctx = ctx;
    
    LOGI("Llama model initialized successfully (n_ctx=1024)");
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
            llama_model_free(state->model);
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

    set_thread_affinity();

    const char *prompt_raw = env->GetStringUTFChars(prompt_str, nullptr);
    std::string prompt(prompt_raw);
    env->ReleaseStringUTFChars(prompt_str, prompt_raw);

    LOGI("generateLlama started. Prompt length: %d", (int)prompt.length());

    const struct llama_vocab * vocab = llama_model_get_vocab(state->model);

    // Tokenize prompt
    int n_tokens_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.length(), nullptr, 0, true, true);
    std::vector<llama_token> prompt_tokens(n_tokens_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.length(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("Error: Tokenization failed");
    }
    LOGI("Tokenization complete. Prompt tokens count: %d", (int)prompt_tokens.size());

    // Reset KV cache to prevent stale context / desync on back-to-back prompts
    llama_memory_t kv = llama_get_memory(state->ctx);
    llama_memory_clear(kv, true);
    state->history_tokens.clear();

    const int n_ctx_max = llama_n_ctx(state->ctx);
    const int n_prompt = (int)prompt_tokens.size();
    if (n_prompt >= n_ctx_max - 16) {
        LOGE("Prompt (%d tokens) exceeds context capacity (%d)", n_prompt, n_ctx_max);
        return env->NewStringUTF("Error: Prompt exceeds context window");
    }

    // Evaluate prompt tokens in batches of up to 512
    for (int i = 0; i < n_prompt; i += 512) {
        int n_eval = std::min(n_prompt - i, 512);
        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        batch.n_tokens = n_eval;
        for (int j = 0; j < n_eval; ++j) {
            batch.token[j] = prompt_tokens[i + j];
            batch.pos[j] = i + j;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (i + j == n_prompt - 1); // Logits computed only for final token
        }

        int decode_res = llama_decode(state->ctx, batch);
        llama_batch_free(batch);
        if (decode_res != 0) {
            LOGE("Llama prompt decode failed with status %d", decode_res);
            llama_memory_clear(kv, true);
            return env->NewStringUTF("Error: Decode failed");
        }
    }

    // Setup sampler
    struct llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string response = "";
    int n_generated = 0;
    int current_pos = n_prompt;

    // Autoregressive token generation loop
    while (n_generated < max_tokens && current_pos < n_ctx_max - 1) {
        llama_token id = llama_sampler_sample(smpl, state->ctx, -1);

        if (llama_vocab_is_eog(vocab, id)) {
            LOGI("EOG token (id=%d) detected, generation stopped", id);
            break;
        }

        char buf[128];
        int n_chars = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            response.append(buf, n_chars);
        }

        n_generated++;

        // Evaluate next token
        llama_batch batch = llama_batch_init(1, 0, 1);
        batch.n_tokens = 1;
        batch.token[0] = id;
        batch.pos[0] = current_pos;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;

        int decode_res = llama_decode(state->ctx, batch);
        llama_batch_free(batch);
        if (decode_res != 0) {
            LOGE("Llama token decode failed with status %d at pos %d", decode_res, current_pos);
            break;
        }

        current_pos++;
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(response.c_str());
}

// Helper to extract the complete UTF-8 prefix and keep any incomplete trailing bytes
static std::string extract_complete_utf8(std::string &buffer) {
    if (buffer.empty()) return "";
    
    size_t len = buffer.length();
    size_t last_start = len;
    
    for (size_t i = len; i > 0; --i) {
        unsigned char b = static_cast<unsigned char>(buffer[i - 1]);
        if ((b & 0xC0) != 0x80) {
            last_start = i - 1;
            break;
        }
    }
    
    if (last_start == len) {
        std::string res = buffer;
        buffer.clear();
        return res;
    }
    
    unsigned char first_byte = static_cast<unsigned char>(buffer[last_start]);
    size_t expected_len = 1;
    if ((first_byte & 0x80) == 0) {
        expected_len = 1;
    } else if ((first_byte & 0xE0) == 0xC0) {
        expected_len = 2;
    } else if ((first_byte & 0xF0) == 0xE0) {
        expected_len = 3;
    } else if ((first_byte & 0xF8) == 0xF0) {
        expected_len = 4;
    }
    
    size_t actual_len = len - last_start;
    if (actual_len < expected_len) {
        std::string complete_prefix = buffer.substr(0, last_start);
        buffer = buffer.substr(last_start);
        return complete_prefix;
    } else {
        std::string complete_prefix = buffer;
        buffer.clear();
        return complete_prefix;
    }
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

    // Reset KV cache to prevent stale context / desync on back-to-back prompts
    llama_memory_t kv = llama_get_memory(state->ctx);
    llama_memory_clear(kv, true);
    state->history_tokens.clear();

    const int n_ctx_max = llama_n_ctx(state->ctx);
    const int n_prompt = (int)prompt_tokens.size();
    if (n_prompt >= n_ctx_max - 16) {
        LOGE("Prompt (%d tokens) exceeds context capacity (%d)", n_prompt, n_ctx_max);
        return env->NewStringUTF("Error: Prompt exceeds context window");
    }

    // Evaluate prompt tokens in batches of up to 512
    for (int i = 0; i < n_prompt; i += 512) {
        int n_eval = std::min(n_prompt - i, 512);
        llama_batch batch = llama_batch_init(n_eval, 0, 1);
        batch.n_tokens = n_eval;
        for (int j = 0; j < n_eval; ++j) {
            batch.token[j] = prompt_tokens[i + j];
            batch.pos[j] = i + j;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (i + j == n_prompt - 1);
        }

        int decode_res = llama_decode(state->ctx, batch);
        llama_batch_free(batch);
        if (decode_res != 0) {
            LOGE("Llama prompt decode failed with status %d", decode_res);
            llama_memory_clear(kv, true);
            return env->NewStringUTF("Error: Decode failed");
        }
    }

    // Setup sampler
    struct llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string response = "";
    std::string utf8_buffer = "";
    int n_generated = 0;
    int current_pos = n_prompt;

    while (n_generated < max_tokens && current_pos < n_ctx_max - 1) {
        llama_token id = llama_sampler_sample(smpl, state->ctx, -1);

        if (llama_vocab_is_eog(vocab, id)) {
            LOGI("EOG token (id=%d) detected, generation stopped", id);
            break;
        }

        char buf[128];
        int n_chars = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            utf8_buffer.append(buf, n_chars);
            response.append(buf, n_chars);
            
            std::string complete_piece = extract_complete_utf8(utf8_buffer);
            if (!complete_piece.empty()) {
                jstring jpiece = env->NewStringUTF(complete_piece.c_str());
                env->CallVoidMethod(callback, onTokenMethod, jpiece);
                env->DeleteLocalRef(jpiece);
            }
        }

        n_generated++;

        // Evaluate next token
        llama_batch batch = llama_batch_init(1, 0, 1);
        batch.n_tokens = 1;
        batch.token[0] = id;
        batch.pos[0] = current_pos;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;

        int decode_res = llama_decode(state->ctx, batch);
        llama_batch_free(batch);
        if (decode_res != 0) {
            LOGE("Llama token decode failed with status %d at pos %d", decode_res, current_pos);
            break;
        }

        current_pos++;
    }

    // Flush any remaining trailing bytes
    if (!utf8_buffer.empty()) {
        jstring jpiece = env->NewStringUTF(utf8_buffer.c_str());
        env->CallVoidMethod(callback, onTokenMethod, jpiece);
        env->DeleteLocalRef(jpiece);
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(response.c_str());
}
