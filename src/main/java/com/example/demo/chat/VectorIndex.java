package com.example.demo.chat;

import com.example.demo.chat.entity.sqlite.VectorStore;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存向量索引：只负责加载、增量和快照读取，不做检索打分。
 * 读取方拿到的是一份不可变快照，避免在打分期间长时间持有读锁。
 */
@Slf4j
final class VectorIndex {

    private final List<float[]> vectors = new ArrayList<>();
    private final Map<Integer, IndexEntry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    void load(List<VectorStore> allVectors) {
        lock.writeLock().lock();
        try {
            vectors.clear();
            entries.clear();
            for (VectorStore vs : allVectors) {
                if (vs.getId() == null) {
                    continue;
                }
                float[] vector = deserialize(vs.getVector());
                if (vector.length == 0) {
                    continue;
                }
                int idx = vs.getId().intValue();
                while (vectors.size() <= idx) {
                    vectors.add(null);
                }
                vectors.set(idx, vector);
                entries.put(idx, new IndexEntry(
                        vs.getDocumentId(),
                        vs.getContent(),
                        vs.getUserId(),
                        vs.getConversationId(),
                        vs.getSourceId(),
                        vs.getMetadataJson()
                ));
            }
            ready.set(true);
            log.info("Vector index loaded with {} vectors", entries.size());
        } catch (RuntimeException e) {
            ready.set(false);
            throw e;
        } finally {
            lock.writeLock().unlock();
        }
    }

    void add(int rowId, String content, float[] vector,
             String documentId, String userId, String conversationId,
             String sourceId, String metadataJson) {
        lock.writeLock().lock();
        try {
            while (vectors.size() <= rowId) {
                vectors.add(null);
            }
            vectors.set(rowId, vector);
            entries.put(rowId, new IndexEntry(
                    documentId, content, userId, conversationId, sourceId, metadataJson));
        } finally {
            lock.writeLock().unlock();
        }
    }

    boolean isReady() {
        return ready.get();
    }

    int size() {
        return entries.size();
    }

    int removeBySource(String sourceId) {
        lock.writeLock().lock();
        try {
            int removed = 0;
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!sourceId.equals(entry.getValue().sourceId)) {
                    continue;
                }
                int rowId = entry.getKey();
                if (rowId >= 0 && rowId < vectors.size()) {
                    vectors.set(rowId, null);
                }
                iterator.remove();
                removed++;
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    void markNotReady() {
        ready.set(false);
    }

    List<IndexedVector> snapshot() {
        lock.readLock().lock();
        try {
            List<IndexedVector> result = new ArrayList<>(entries.size());
            for (Map.Entry<Integer, IndexEntry> entry : entries.entrySet()) {
                int rowId = entry.getKey();
                if (rowId < 0 || rowId >= vectors.size()) {
                    continue;
                }
                float[] vector = vectors.get(rowId);
                if (vector == null || entry.getValue() == null) {
                    continue;
                }
                result.add(new IndexedVector(vector, entry.getValue()));
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    List<IndexEntry> publicEntries() {
        lock.readLock().lock();
        try {
            List<IndexEntry> result = new ArrayList<>();
            for (IndexEntry entry : entries.values()) {
                if (entry != null && entry.isPublicKnowledge()) {
                    result.add(entry);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    record IndexedVector(float[] vector, IndexEntry entry) {
    }

    private static float[] deserialize(byte[] bytes) {
        if (bytes == null || bytes.length % 4 != 0) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
