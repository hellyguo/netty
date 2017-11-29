/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.<br>
 * 基于FastThreadLocal的对象池，需要显式回收
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {//屏蔽了POOL后的返回对象，在回收时不做任何处理
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);//ID生成器
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();//拥有者标识
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 32768; // Use 32k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        //核数乘2，与EventLoop值相同，默认最多可延迟回收这么多
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        // 回收率，默认只回收Queue中n%ratio=0的值，其他都丢弃。这是为了应对大请求毛刺，让pool大小不会快速扩大，而是慢慢增长
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int ratioMask;
    private final int maxDelayedQueuesPerThread;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {//等于0，相当于屏蔽了POOL功能
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {//获取目标对象
        if (maxCapacityPerThread == 0) {//等于0，相当于屏蔽了POOL功能，每次都返回新对象，传入的handle是默认的无操作处理器
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        Stack<T> stack = threadLocal.get();//从线程本地环境中取出
        DefaultHandle<T> handle = stack.pop();//取出对象
        if (handle == null) {//为空，新建
            handle = stack.newHandle();//新建handle
            handle.value = newObject(handle);//基于handle创建目标对象
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.<br>
     * 已废弃方法。不过也可以看出如何操作的。
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    /** 处理器，主要是为了后续回收 */
    public interface Handle<T> {
        void recycle(T object);
    }

    /** 默认处理器，实现了回收机制 */
    static final class DefaultHandle<T> implements Handle<T> {
        private int lastRecycledId;
        private int recycleId;

        boolean hasBeenRecycled;

        private Stack<?> stack;/** 栈，主要数据结构之一 */
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        /** 回收操作，基于栈 */
        @Override
        public void recycle(Object object) {
            if (object != value) {//只有持有对象句柄的才能进行回收
                throw new IllegalArgumentException("object does not belong to handle");
            }
            stack.push(this);//推入栈中，供下一次调用
        }
    }

    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    /** 队列，主要数据结构之一 */
    private static final class WeakOrderQueue {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        /** 基于{@link AtomicInteger}，自身是写入索引，额外增加了读取索引readIndex */
        @SuppressWarnings("serial")
        private static final class Link extends AtomicInteger {
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];//处理器数组，默认大小是16

            private int readIndex;
            private Link next;
        }

        // chain of data items
        private Link head, tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final WeakReference<Thread> owner;//拥有者线程的弱引用
        private final int id = ID_GENERATOR.getAndIncrement();//每一个QUEUE分配一个ID
        private final AtomicInteger availableSharedCapacity;

        private WeakOrderQueue() {
            owner = null;
            availableSharedCapacity = null;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            head = tail = new Link();
            owner = new WeakReference<Thread>(thread);

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            availableSharedCapacity = stack.availableSharedCapacity;//指向stack的同一对象
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);
            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;//确保没有循环指向
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            return reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? WeakOrderQueue.newQueue(stack, thread) : null;
        }

        /** 锁定空间，看stack中是否还有剩余空间，不够报失败，够就一下减下 */
        private static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
            assert space >= 0;
            for (;;) {
                int available = availableSharedCapacity.get();
                if (available < space) {
                    return false;
                }
                if (availableSharedCapacity.compareAndSet(available, available - space)) {//availableSharedCapacity只在这里减小
                    return true;
                }
            }
        }

        /** 回收空间 */
        private void reclaimSpace(int space) {
            assert space >= 0;
            availableSharedCapacity.addAndGet(space);//availableSharedCapacity复增
        }

        /** 非拥有者线程回收，加入待回收队列 */
        void add(DefaultHandle<?> handle) {
            handle.lastRecycledId = id;//标记为非拥有者回收

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {//触及上限，默认是16
                if (!reserveSpace(availableSharedCapacity, LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                // 新建Link节点
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);
        }

        /** 还有需要回收的数据 */
        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head;
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {//读取下标已经到顶
                if (head.next == null) {//没有下一个，结束
                    return false;
                }
                this.head = head = head.next;//指向下一个，读总是落后于写，所以可以这样
            }

            final int srcStart = head.readIndex;//未读起点
            int srcEnd = head.get();//未读终点
            final int srcSize = srcEnd - srcStart;//可回收空间
            if (srcSize == 0) {//没有可回收空间
                return false;
            }

            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;//新大小

            if (expectedCapacity > dst.elements.length) {//扩容，适配新大小
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;//head为Link对象
                final DefaultHandle[] dstElems = dst.elements;//dst为Stack对象
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;//标记为前次回收者，两者相同，标志着回收成功
                    } else if (element.recycleId != element.lastRecycledId) {//已回收过，报错
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;//置空Queue管理的Link下属数组项

                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;//将归属stack指向dst
                    dstElems[newDstSize ++] = element;//放入数组，并将兼做下标的newDstSize增加
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    reclaimSpace(LINK_CAPACITY);

                    this.head = head.next;
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                super.finalize();
            } finally {
                // We need to reclaim all space that was reserved by this WeakOrderQueue so we not run out of space in
                // the stack. This is needed as we not have a good life-time control over the queue as it is used in a
                // WeakHashMap which will drop it at any time.
                // 不得已而为之的操作，通过这个方法来回收，不然availableSharedCapacity不会还原
                Link link = head;
                while (link != null) {
                    reclaimSpace(LINK_CAPACITY);
                    link = link.next;
                }
            }
        }
    }

    /** 栈，主要数据结构之一 */
    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;
        final Thread thread;
        final AtomicInteger availableSharedCapacity;
        final int maxDelayedQueues;

        private final int maxCapacity;
        private final int ratioMask;
        private DefaultHandle<?>[] elements;
        private int size;
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.计数器，当count%ratio=0时进行queue内数据回收。
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;//反向父句柄
            this.thread = thread;//线程句柄
            this.maxCapacity = maxCapacity;//容量
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));//可放Queue中共享回收的最大数
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];//数据存储数组
            this.ratioMask = ratioMask;//掩码
            this.maxDelayedQueues = maxDelayedQueues;//延迟处理队列数
        }

        // Marked as synchronized to ensure this is serialized.
        // 怕多线程下head漂没了
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {//回收失败
                    return null;
                }
                //回收成功，更新空闲大小
                size = this.size;
            }
            size --;//分配，数量减一。同时该值也作为分配下标。
            DefaultHandle ret = elements[size];
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            //重置回收标识
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }

        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (thread == currentThread) {//同线程调用，直接处理
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {//不同线程调用，放入待处理队列，延后处理
                // The current Thread is not the one that belongs to the Stack, we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            if ((item.recycleId | item.lastRecycledId) != 0) {//不能二次回收
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;//表示这是被拥有者处理回收的

            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {//超容量或需要丢弃最新的
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            if (size == elements.length) {//满了，扩容
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                if (delayedRecycled.size() >= maxDelayedQueues) {//数量多于可延迟队列数
                    // Add a dummy queue so we know we should drop the object
                    // DUMMY标记表明后续是丢弃的
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // 分配失败，丢弃
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            queue.add(item);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {//回收处理标识，一次一定进，二次不会进
                if ((++handleRecycleCount & ratioMask) != 0) {//基于RATIO进行筛选回收，默认值是8，n%ratio==0才回收
                    // Drop the object.
                    return true;
                }
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        /** 创建带有栈自身句柄的处理器对象 */
        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
