#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model   *g_model = nullptr;
static llama_context *g_ctx   = nullptr;
static llama_sampler *g_smpl  = nullptr;

// Fallback prompt format for when the model's chat template is not one of the
// templates llama_chat_apply_template understands. Qwen models use ChatML.
static std::string build_chatml(const std::vector<llama_chat_message> &msgs) {
    std::string p;
    for (const auto &m : msgs) {
        p += "<|im_start|>";
        p += m.role;
        p += "\n";
        p += m.content;
        p += "<|im_end|>\n";
    }
    p += "<|im_start|>assistant\n";
    return p;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_codvika_llama_LlamaBridge_loadModel(JNIEnv *env, jobject, jstring modelPath,
                                             jint nThreads, jint nCtx) {
    llama_backend_init();

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!g_model) {
        LOGE("failed to load model");
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) nCtx;
    cparams.n_batch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return JNI_TRUE;
}

// Returns the assistant reply as UTF-8 bytes (Kotlin converts to String; this
// avoids NewStringUTF aborting on byte sequences that are not modified UTF-8).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_codvika_llama_LlamaBridge_chat(JNIEnv *env, jobject, jobjectArray roles,
                                        jobjectArray contents, jint maxTokens) {
    std::string out;
    if (!g_model || !g_ctx || !g_smpl) {
        return env->NewByteArray(0);
    }

    // Collect the conversation from Java.
    jsize n_msg = env->GetArrayLength(roles);
    std::vector<std::string> role_s(n_msg), content_s(n_msg);
    std::vector<llama_chat_message> msgs(n_msg);
    for (jsize i = 0; i < n_msg; ++i) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        auto jc = (jstring) env->GetObjectArrayElement(contents, i);
        const char *r = env->GetStringUTFChars(jr, nullptr);
        const char *c = env->GetStringUTFChars(jc, nullptr);
        role_s[i] = r;
        content_s[i] = c;
        env->ReleaseStringUTFChars(jr, r);
        env->ReleaseStringUTFChars(jc, c);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        msgs[i] = {role_s[i].c_str(), content_s[i].c_str()};
    }

    // Format the prompt with the model's chat template, falling back to ChatML.
    std::string prompt;
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    std::vector<char> buf(8192);
    int32_t plen = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                             buf.data(), (int32_t) buf.size());
    if (plen > (int32_t) buf.size()) {
        buf.resize(plen + 1);
        plen = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                         buf.data(), (int32_t) buf.size());
    }
    if (plen > 0) {
        prompt.assign(buf.data(), plen);
    } else {
        prompt = build_chatml(msgs);
    }

    // Tokenize.
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    int32_t n_tok = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                                    nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_tok);
    llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                   tokens.data(), n_tok, true, true);

    const int32_t n_ctx = (int32_t) llama_n_ctx(g_ctx);
    if (n_tok >= n_ctx - maxTokens) {
        LOGE("prompt too long: %d tokens for n_ctx=%d", n_tok, n_ctx);
        return env->NewByteArray(0);
    }

    // Fresh conversation each call: the full history is re-sent from Kotlin.
    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_sampler_reset(g_smpl);

    // Prefill the prompt in n_batch-sized chunks.
    const int32_t n_batch = 512;
    for (int32_t i = 0; i < n_tok; i += n_batch) {
        int32_t chunk = std::min(n_batch, n_tok - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during prefill");
            return env->NewByteArray(0);
        }
    }

    // Generate.
    for (int32_t i = 0; i < maxTokens; ++i) {
        llama_token tok = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[512];
        int32_t pn = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (pn > 0) out.append(piece, pn);

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
    }

    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(),
                            reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_codvika_llama_LlamaBridge_unload(JNIEnv *, jobject) {
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
}
