package com.harmony.backend.modules.chat.service.orchestration;

import com.harmony.backend.modules.chat.service.orchestration.model.ChatIdempotencyGate;
import com.harmony.backend.modules.chat.service.orchestration.model.PreparedChatStream;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatStreamOrchestrationService {

    public Flux<String> execute(PreparedChatStream prepared,
                                ChatIdempotencyGate idempotency,
                                ChatStreamCallbacks callbacks) {
        if (prepared.getImmediateResponse() != null) {
            return Flux.just(prepared.getImmediateResponse());
        }
        Flux<String> sourceStream = callbacks.createSourceStream(prepared);
        AtomicReference<StringBuilder> assistantBuffer = new AtomicReference<>(new StringBuilder());
        AtomicReference<Boolean> toolCallDetected = new AtomicReference<>(false);
        AtomicReference<Boolean> toolSuspected = new AtomicReference<>(false);

        Flux<String> stream = Flux.create(sink -> {
            AtomicBoolean finalized = new AtomicBoolean(false);
            AtomicBoolean streamingMarked = new AtomicBoolean(false);
            Disposable disposable = sourceStream.subscribe(
                    chunk -> {
                        assistantBuffer.get().append(chunk);
                        if (streamingMarked.compareAndSet(false, true)) {
                            callbacks.onStreamingStart(prepared);
                        }
                        String current = assistantBuffer.get().toString().trim();
                        if (!toolSuspected.get() && current.startsWith("{")) {
                            toolSuspected.set(true);
                        }
                        if (!toolCallDetected.get() && callbacks.isLikelyToolCall(current)) {
                            toolCallDetected.set(true);
                        }
                        if (!prepared.isBufferToolStream() && !toolSuspected.get() && !toolCallDetected.get()) {
                            sink.next(chunk);
                        }
                    },
                    error -> Mono.fromRunnable(() -> callbacks.onFailure(prepared, idempotency, assistantBuffer.get().toString(), error))
                            .subscribeOn(Schedulers.boundedElastic())
                            .doFinally(signal -> {
                                if (finalized.compareAndSet(false, true)) {
                                    sink.error(error);
                                }
                            })
                            .subscribe(),
                    () -> Mono.fromRunnable(() -> {
                                String assistantContent = assistantBuffer.get().toString();
                                String finalContent = callbacks.resolveFinalContent(prepared, assistantContent);
                                if (prepared.isBufferToolStream() || toolSuspected.get() || toolCallDetected.get()) {
                                    callbacks.emitChunked(sink, finalContent);
                                }
                                callbacks.onSuccess(prepared, idempotency, finalContent);
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnError(error -> {
                                callbacks.onFailure(prepared, idempotency, assistantBuffer.get().toString(), error);
                                if (finalized.compareAndSet(false, true)) {
                                    sink.error(error);
                                }
                            })
                            .doOnSuccess(v -> {
                                if (finalized.compareAndSet(false, true)) {
                                    sink.complete();
                                }
                            })
                            .subscribe()
            );
            sink.onCancel(() -> {
                disposable.dispose();
                if (finalized.compareAndSet(false, true)) {
                    callbacks.onCancel(prepared, idempotency, assistantBuffer.get().toString());
                }
            });
        });

        if (prepared.getWarning() != null) {
            return Flux.just(prepared.getWarning()).concatWith(stream);
        }
        return stream;
    }

    public interface ChatStreamCallbacks {
        Flux<String> createSourceStream(PreparedChatStream prepared);

        void onStreamingStart(PreparedChatStream prepared);

        String resolveFinalContent(PreparedChatStream prepared, String assistantContent);

        void onSuccess(PreparedChatStream prepared, ChatIdempotencyGate gate, String finalContent);

        void onFailure(PreparedChatStream prepared, ChatIdempotencyGate gate, String partialContent, Throwable error);

        void onCancel(PreparedChatStream prepared, ChatIdempotencyGate gate, String partialContent);

        boolean isLikelyToolCall(String content);

        void emitChunked(FluxSink<String> sink, String text);
    }
}
