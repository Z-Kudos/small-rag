package com.smallrag.ragmcp.rag;

/**
 * 重建任务的写权限已被撤销，拒绝继续写入索引。
 *
 * <p>看门狗硬超时先撤销写权限（{@link ReindexListener#isWritable()} 变为 false），
 * 再中断线程；旧任务即使“活过来”，也会在下一个写点撞到这个异常而终止，
 * 因此新任务可以在宽限期后安全接管。
 *
 * @author zjk
 */
public class ReindexRevokedException extends RuntimeException {

    public ReindexRevokedException(String message) {
        super(message);
    }
}
