package io.netty.example.myTest;

import io.netty.util.concurrent.FastThreadLocal;

import java.util.concurrent.TimeUnit;

public class FastThreadLocalTest {

    private static FastThreadLocal<Object>threadLocal=new FastThreadLocal<Object>(){
        @Override
        protected Object initialValue() throws Exception {
            return new Object();
        }
    };

    public static void main(String[] args) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Object object=threadLocal.get();
//                do...with object
                System.out.println(object);
                while (true) {
                    threadLocal.set(new Object());
                    try {
                        TimeUnit.MILLISECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                Object object=threadLocal.get();
//                ...do with object
                System.out.println(object);
                while (true) {
                    System.out.println(threadLocal.get()==object);
                    try {
                        TimeUnit.MILLISECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();

    }

}
