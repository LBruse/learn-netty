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
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
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
                        Runtime.getRuntime().availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
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

    /**
     * 每个Recycler对于每个线程都会有一个对应的Stack
     */
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
//            maxCapacityPerThread默认为32K, maxSharedCapacityFactor默认为2,
//            ratioMask默认为7, maxDelayedQueuesPerThread默认为2*CPU核数
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }
    };

    protected Recycler() {
//        线程最大能放32768个对象[32k]
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
//        MAX_SHARED_CAPACITY_FACTOR 默认为2
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
//        maxCapacityPerThread默认为32K,maxSharedCapacityFactor默认为2
//        ratio默认值为8,MAX_DELAYED_QUEUES_PER_THREAD默认值为2*CPU核数
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
//        ratioMask为7,ratio默认为8
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
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
    public final T get() {
//        如果最大能存放对象数量==0,不进行存储,直接返回一个创建一个对象并返回
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
//        获取当前线程的Stack
        Stack<T> stack = threadLocal.get();
//        从Stack中弹出一个对象
        DefaultHandle<T> handle = stack.pop();
//        如果当前弹出Handle==null
        if (handle == null) {
//            创建一个Handle
            handle = stack.newHandle();
//            为Handle的value赋值
            handle.value = newObject(handle);
        }
//        当前弹出Handle不为null,直接返回Handle.value
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
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

    public interface Handle<T> {
        void recycle(T object);
    }

    static final class DefaultHandle<T> implements Handle<T> {
        private int lastRecycledId;
        private int recycleId;

        boolean hasBeenRecycled;

//        一个Handle绑定一个Stack
        private Stack<?> stack;
//        一个Handle绑定一个Value
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }
//            [内存缓存]把当前cache.entry压到stack中
//            [对象回收]把对象压到栈中
            stack.push(this);
        }
    }

    /**
     * 每个线程都有一个Map,每个Map都有不同的Stack,不同的Stack对应不同线程的WeakOrderQueue
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        private static final class Link extends AtomicInteger {
//            每次都创建LINK_CAPACITY个元素,默认为16
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            private int readIndex;
            private Link next;
        }

        // chain of data items
        private Link head, tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final WeakReference<Thread> owner;
        private final int id = ID_GENERATOR.getAndIncrement();
        private final AtomicInteger availableSharedCapacity;

        private WeakOrderQueue() {
            owner = null;
            availableSharedCapacity = null;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            head = tail = new Link();
//            thread:线程B,即当前回收对象线程
            owner = new WeakReference<Thread>(thread);
            /*
             *  [WeakOrderQueue与Stack绑定]
             * 首次分配时,线程A的Stack.head=null,所以next=null,stack.head=线程B的WeakOrderQueue
             * 下次分配时,线程A的Stack.head=线程B的WeakOrderQueue,
             * 所以next=线程B的WeakOrderQueue,stack.head=线程C的WeakOrderQueue
             * 组成一个链表,next指向别的线程的WeakOrderQueue
             * 每次别的线程给线程A的Stack分配1个WeakOrderQueue的时候,都从头部进行插入
             */
            synchronized (stack) {
                next = stack.head;
                stack.head = this;
            }

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            availableSharedCapacity = stack.availableSharedCapacity;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            // 判断当前线程A stack还能不能分配LINK_CAPACITY个内存,
            // 如果不能分配,直接返回null
            // 如果能分配,直接创建一个WeakOrderQueue
            return reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? new WeakOrderQueue(stack, thread) : null;
        }

        private static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
            assert space >= 0;
            for (;;) {
                int available = availableSharedCapacity.get();
                if (available < space) {
                    return false;
                }
                // 经过一系列操作后,更新线程A的Stack可给其它线程共享的回收对象个数
                // 当线程A的Stack可给其它线程共享的回收对象个数不足时,其它线程就无法再给线程A回收对象了
                if (availableSharedCapacity.compareAndSet(available, available - space)) {
                    return true;
                }
            }
        }

        private void reclaimSpace(int space) {
            assert space >= 0;
//            说明已经释放掉1个link,可以往WeakOrderQueue中添加的link数量++
            availableSharedCapacity.addAndGet(space);
        }

        void add(DefaultHandle<?> handle) {
//            将lastRecycledId设置为当前WeakOrderQueue的id,表示handle是由当前WeakOrderQueue进行回收的
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;
//            当前link已经不可写
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
//                判断线程A是否允许别的线程回收一定数量对象
                if (!reserveSpace(availableSharedCapacity, LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                // 线程A允许别的线程继续回收一定数量对象,创建新的link
                this.tail = tail = tail.next = new Link();
                // 获取写指针
                writeIndex = tail.get();
            }
//            确保当前WeakOrderQueue尾部的link有足够的空间,把handle追加到link中
            tail.elements[writeIndex] = handle;
//            重置handle的stack
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
//            更新写指针
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
//            获取当前link链表[即数组]头节点
            Link head = this.head;
//            头节点==null,说明该link链表已经没数据了,直接return false
            if (head == null) {
                return false;
            }

//            读指针==link链表的大小,说明该link链表的元素都已经被取走了
            if (head.readIndex == LINK_CAPACITY) {
//                说明WeakOrderQueue已经没有下一个link链表了,直接return false
                if (head.next == null) {
                    return false;
                }
//                WeakOrderQueue还有下一个link链表,指向下一个link链表
                this.head = head = head.next;
            }
//            获取link链表的读指针
            final int srcStart = head.readIndex;
//            获取link链表的长度
            int srcEnd = head.get();
//            计算link链表可以传输多少对象到Stack中
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }
//            计算当前Stack容量
            final int dstSize = dst.size;
//            计算如果把link链表[数组]的对象全部传输到Stack中,Stack最大的容量会变成多少
            final int expectedCapacity = dstSize + srcSize;
//            如果Stack最大容量>Stack的大小,进行扩容
            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
//                计算当前link链表可以向Stack传输的最后1个对象
//                actualCapacity - dstSize:可以向Stack传输多少对象,
//                srcStart+ actualCapacity - dstSize:表示最后1个对象不能超过这个值
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
//                拿到当前link链表数组
                final DefaultHandle[] srcElems = head.elements;
//                拿到Stack的数组
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
//                不断地把当前link链表数组元素传输到Stack的数组中
                for (int i = srcStart; i < srcEnd; i++) {
//                    回收对象,就是简单的赋值操作
                    DefaultHandle element = srcElems[i];
//                    recycleId == 0 :表示该对象没有被回收
                    if (element.recycleId == 0) {
//                        修改recycleId,表明该对象已被回收
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
//                        recycleId如果不等于lastRecycledId,说明该对象已被其它线程回收过,抛出异常
                        throw new IllegalStateException("recycled already");
                    }
//                    回收完毕,将link对应位置的对象赋值为null
                    srcElems[i] = null;
//                    判断是否丢弃对象
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }
//                说明当前link链表已被回收完毕,且当前link的下一个节点不为null
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    reclaimSpace(LINK_CAPACITY);
//                    head节点指向head节点的下一个节点,旧的head节点自然会被GC掉
                    this.head = head.next;
                }
//                把读指针指向srcEnd
                head.readIndex = srcEnd;
//                说明没有向Stack传输任何对象,直接return false
                if (dst.size == newDstSize) {
                    return false;
                }
//                更新Stack大小
                dst.size = newDstSize;
//                返回true,说明已经从异线程中成功收割对象
                return true;
            } else {
                // The destination stack is full already.
//                当前Stack已经满了,直接return false
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
                Link link = head;
                while (link != null) {
                    reclaimSpace(LINK_CAPACITY);
                    link = link.next;
                }
            }
        }
    }

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
//        Stack存储的一系列对象[每个Handle都包装了一个对象,且Handle可被外部对象进行应用,从而供外部调用Handle回收对象]
        private DefaultHandle<?>[] elements;
        private int size;
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
//            Stack 与一个 Thread 做唯一绑定
            this.thread = thread;
//            Stack 最大容量
            this.maxCapacity = maxCapacity;
//            当前线程创建的对象,能够在其它线程中缓存的最大个数
//            maxCapacity[32K] / maxSharedCapacityFactor[2] = 16K
//            LINK_CAPACITY = 16
//            即本线程能存放对象上限为32K,其它线程缓存本线程的对象上限为16K
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
//            控制对象回收频率[并不是每一次都可以把对象进行回收,通过ratioMask控制回收比率]
            this.ratioMask = ratioMask;
//            当前线程创建的对象,最终能够释放的线程数有多少[即当前线程创建的对象,能够在多少个线程进行缓存]
            this.maxDelayedQueues = maxDelayedQueues;
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
//            获取当前Stack元素个数
            int size = this.size;
//            当前Stack元素个数==0
            if (size == 0) {
//                从其他线程回收对象
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
            }
//            当前Stack元素个数--
            size --;
//            获取一个Handle
            DefaultHandle ret = elements[size];
//            把相应下标元素设置为null
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
//            更新Stack.size
            this.size = size;
//            返回弹出的Handle
            return ret;
        }

        boolean scavenge() {
//            如果已经回收到一些对象,直接return true
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }
//            重置节点
            // reset our scavenge cursor
            prev = null;
//            下次从头部开始进行回收
            cursor = head;
            return false;
        }

        boolean scavengeSome() {
//            当前需要回收的WeakOrderQueue
            WeakOrderQueue cursor = this.cursor;
//            如果当前需要回收的WeakOrderQueue==null
            if (cursor == null) {
//                获取当前头节点
                cursor = head;
//                当前头节点==null
                if (cursor == null) {
//                    当前Stack已经没有关联的WeakOrderQueue,直接return false
                    return false;
                }
            }
//            尝试从Stack关联的WeakOrderQueue链表中回收对象
            boolean success = false;
            WeakOrderQueue prev = this.prev;
            do {
//                尝试把WeakOrderQueue中的对象传输到Stack中,回收成功则结束循环
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }

//                拿到WeakOrderQueue链表中的下一个节点
                WeakOrderQueue next = cursor.next;
//                WeakOrderQueue绑定线程已不存在,做一些清理工作,并把该WeakOrderQueue从链表中移除
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
//                    当前WeakOrderQueue有数据,尝试把WeakOrderQueue中的数据传输到Stack中
                    if (cursor.hasFinalData()) {
                        for (;;) {
//                            每次都把WeakOrderQueue中的1个link传输到Stack中
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }
//                    把本WeakOrderQueue的前节点的下一个节点指向本WeakOrderQueue节点的下一个节点
                    if (prev != null) {
                        prev.next = next;
                    }
                } else {
//                    WeakOrderQueue绑定线程仍存在,把指针往下移动
                    prev = cursor;
                }
//                WeakOrderQueue绑定线程仍存在,把指针往下移动
                cursor = next;
//            当当前WeakOrderQueue==null && 当前收割对象成功时,终止循环
            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
//            获取当前线程
            Thread currentThread = Thread.currentThread();
//            当前线程==与Stack绑定的线程
            if (thread == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
//                直接把对象塞到Stack中
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack, we need to signal that the push
                // happens later.
//                晚点把对象塞到Stack中
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
//            OWN_THREAD_ID在整个Recycler中是唯一固定的
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
//            当前Stack元素个数>=可存放元素个数上限,直接drop对象
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
//            数组不足以存放
            if (size == elements.length) {
//                创建一个2倍大小的新数组,并把内容COPY到新数组
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }
//            将Handle添加数组中
            elements[size] = item;
//            数组元素个数++
            this.size = size + 1;
        }

        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            // 获取线程B的WeakOrderQueue
            WeakOrderQueue queue = delayedRecycled.get(this);
            // 表示当前线程B从未回收过线程A的对象
            if (queue == null) {
                // 当前线程B已经回收过的线程个数 >= maxDelayedQueues
                // 当前线程B已经不能再回收其它线程的对象
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    // 传进一个假的WeakOrderQueue
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // stack:是在线程A中维护的;
                // thread:线程B
                // 创建一个WeakOrderQueue,即为当前线程B分配1个线程A的Stack对应的WeakOrderQueue
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                // 把当前Stack和对应的WeakOrderQueue放到Map中
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // 如果线程B的WeakOrderQueue===假的WeakOrderQueue,什么也不做
                // drop object
                return;
            }
            // 将对象追加到WeakOrderQueue
            queue.add(item);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
//            判断Handle以前没有被回收过
            if (!handle.hasBeenRecycled) {
//                ++handleRecycleCount:当前为止一共回收了多少个Handle
//                ratioMask:默认为7
//                意思是每隔8个对象就会来判断1次
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
//                    返回true,表示只回收1/8的对象
                    return true;
                }
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
//            一个Handle绑定一个Stack
            return new DefaultHandle<T>(this);
        }
    }
}
