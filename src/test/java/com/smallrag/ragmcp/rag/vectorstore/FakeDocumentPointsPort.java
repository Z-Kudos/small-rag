package com.smallrag.ragmcp.rag.vectorstore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 测试用内存实现 {@link DocumentPointsPort}：记录调用历史，可配置返回值。
 *
 * @author zjk
 */
public final class FakeDocumentPointsPort implements DocumentPointsPort {

    /** findPointIds 的结果；默认 Optional.empty()（读取失败，走先删后写）。 */
    public Optional<Set<String>> findPointIdsResult = Optional.empty();

    /** fetchParents 的结果；默认空 map（触发父块缺失时的子块回退）。 */
    public Map<String, ParentPoint> parents = Map.of();

    /** cleanupOrphans 的结果。 */
    public CleanupResult cleanupResult = new CleanupResult(0, 0);

    /** 非 null 时 deletePoints 抛出该异常。 */
    public IllegalStateException deleteFailure;

    /** 非 null 时 cleanupOrphans 抛出该异常。 */
    public IllegalStateException cleanupFailure;

    public final List<Set<String>> deletedBatches = new ArrayList<>();
    public int cleanupCalls;
    public int fetchParentsCalls;

    @Override
    public Optional<Set<String>> findPointIds(String docId) {
        return findPointIdsResult;
    }

    @Override
    public void deletePoints(Set<String> pointIds) throws IllegalStateException {
        if (deleteFailure != null) {
            throw deleteFailure;
        }
        deletedBatches.add(new LinkedHashSet<>(pointIds));
    }

    @Override
    public CleanupResult cleanupOrphans() throws IllegalStateException {
        cleanupCalls++;
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
        return cleanupResult;
    }

    @Override
    public Map<String, ParentPoint> fetchParents(Set<String> parentIds) {
        fetchParentsCalls++;
        return parents;
    }
}
