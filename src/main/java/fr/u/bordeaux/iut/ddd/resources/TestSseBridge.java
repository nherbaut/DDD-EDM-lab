package fr.u.bordeaux.iut.ddd.resources;

import jakarta.enterprise.context.ApplicationScoped;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class TestSseBridge {

    private static final class StreamState {
        private final Queue<String> pending = new ConcurrentLinkedQueue<>();
        private volatile MultiEmitter<? super String> emitter;
        private volatile boolean completed;
    }

    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();

    public Multi<String> register(String correlationId) {
        StreamState state = streams.computeIfAbsent(correlationId, ignored -> new StreamState());
        return Multi.createFrom().<String>emitter(emitter -> {
            state.emitter = emitter;

            String next;
            while ((next = state.pending.poll()) != null) {
                emitter.emit(next);
            }

            if (state.completed) {
                streams.remove(correlationId);
                emitter.complete();
                return;
            }

            emitter.onTermination(() -> streams.remove(correlationId));
        });
    }

    public void emit(String correlationId, String payload) {
        StreamState state = streams.computeIfAbsent(correlationId, ignored -> new StreamState());
        MultiEmitter<? super String> emitter = state.emitter;
        if (emitter == null) {
            state.pending.add(payload);
            return;
        }
        emitter.emit(payload);
    }

    public void emitIfConnected(String key, String payload) {
        StreamState state = streams.get(key);
        if (state == null || state.completed) {
            return;
        }
        MultiEmitter<? super String> emitter = state.emitter;
        if (emitter == null) {
            return;
        }
        emitter.emit(payload);
    }

    public void complete(String correlationId) {
        StreamState state = streams.computeIfAbsent(correlationId, ignored -> new StreamState());
        state.completed = true;
        MultiEmitter<? super String> emitter = state.emitter;
        if (emitter == null) {
            return;
        }
        streams.remove(correlationId);
        emitter.complete();
    }
}
