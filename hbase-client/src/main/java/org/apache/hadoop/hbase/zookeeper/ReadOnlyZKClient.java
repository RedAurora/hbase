/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.zookeeper;

import static org.apache.hadoop.hbase.HConstants.DEFAULT_ZK_SESSION_TIMEOUT;
import static org.apache.hadoop.hbase.HConstants.ZK_SESSION_TIMEOUT;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hbase.thirdparty.com.google.common.annotations.VisibleForTesting;

/**
 * A very simple read only zookeeper implementation without watcher support.
 */
@InterfaceAudience.Private
public final class ReadOnlyZKClient implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(ReadOnlyZKClient.class);

  public static final String RECOVERY_RETRY = "zookeeper.recovery.retry";

  private static final int DEFAULT_RECOVERY_RETRY = 30;

  public static final String RECOVERY_RETRY_INTERVAL_MILLIS =
      "zookeeper.recovery.retry.intervalmill";

  private static final int DEFAULT_RECOVERY_RETRY_INTERVAL_MILLIS = 1000;

  public static final String KEEPALIVE_MILLIS = "zookeeper.keep-alive.time";

  private static final int DEFAULT_KEEPALIVE_MILLIS = 60000;

  private static final EnumSet<Code> FAIL_FAST_CODES = EnumSet.of(Code.NOAUTH, Code.AUTHFAILED);

  private final String connectString;

  private final int sessionTimeoutMs;

  private final int maxRetries;

  private final int retryIntervalMs;

  private final int keepAliveTimeMs;

  private static abstract class Task implements Delayed {

    protected long time = System.nanoTime();

    public boolean needZk() {
      return false;
    }

    public void exec(ZooKeeper zk) {
    }

    public void connectFailed(IOException e) {
    }

    public void closed(IOException e) {
    }

    @Override
    public int compareTo(Delayed o) {
      Task that = (Task) o;
      int c = Long.compare(time, that.time);
      if (c != 0) {
        return c;
      }
      return Integer.compare(System.identityHashCode(this), System.identityHashCode(that));
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(time - System.nanoTime(), TimeUnit.NANOSECONDS);
    }
  }

  private static final Task CLOSE = new Task() {
  };

  private final DelayQueue<Task> tasks = new DelayQueue<>();

  private final AtomicBoolean closed = new AtomicBoolean(false);

  private ZooKeeper zookeeper;

  private String getId() {
    return String.format("0x%08x", System.identityHashCode(this));
  }

  public ReadOnlyZKClient(Configuration conf) {
    this.connectString = ZKConfig.getZKQuorumServersString(conf);
    this.sessionTimeoutMs = conf.getInt(ZK_SESSION_TIMEOUT, DEFAULT_ZK_SESSION_TIMEOUT);
    this.maxRetries = conf.getInt(RECOVERY_RETRY, DEFAULT_RECOVERY_RETRY);
    this.retryIntervalMs =
        conf.getInt(RECOVERY_RETRY_INTERVAL_MILLIS, DEFAULT_RECOVERY_RETRY_INTERVAL_MILLIS);
    this.keepAliveTimeMs = conf.getInt(KEEPALIVE_MILLIS, DEFAULT_KEEPALIVE_MILLIS);
    LOG.info("Start read only zookeeper connection " + getId() + " to " + connectString +
        ", session timeout " + sessionTimeoutMs + " ms, retries " + maxRetries +
        ", retry interval " + retryIntervalMs + " ms, keep alive " + keepAliveTimeMs + " ms");
    Thread t = new Thread(this::run, "ReadOnlyZKClient");
    t.setDaemon(true);
    t.start();
  }

  private abstract class ZKTask<T> extends Task {

    protected final String path;

    private final CompletableFuture<T> future;

    private final String operationType;

    private int retries;

    protected ZKTask(String path, CompletableFuture<T> future, String operationType) {
      this.path = path;
      this.future = future;
      this.operationType = operationType;
    }

    protected final void onComplete(ZooKeeper zk, int rc, T ret, boolean errorIfNoNode) {
      tasks.add(new Task() {

        @Override
        public void exec(ZooKeeper alwaysNull) {
          Code code = Code.get(rc);
          if (code == Code.OK) {
            future.complete(ret);
          } else if (code == Code.NONODE) {
            if (errorIfNoNode) {
              future.completeExceptionally(KeeperException.create(code, path));
            } else {
              future.complete(ret);
            }
          } else if (FAIL_FAST_CODES.contains(code)) {
            future.completeExceptionally(KeeperException.create(code, path));
          } else {
            if (code == Code.SESSIONEXPIRED) {
              LOG.warn(getId() + " session expired, close and reconnect");
              try {
                zk.close();
              } catch (InterruptedException e) {
              }
            }
            if (ZKTask.this.delay(retryIntervalMs, maxRetries)) {
              LOG.warn(getId() + " failed for " + operationType + " of " + path + ", code = " +
                  code + ", retries = " + ZKTask.this.retries);
              tasks.add(ZKTask.this);
            } else {
              LOG.warn(getId() + " failed for " + operationType + " of " + path + ", code = " +
                  code + ", retries = " + ZKTask.this.retries + ", give up");
              future.completeExceptionally(KeeperException.create(code, path));
            }
          }
        }

        @Override
        public void closed(IOException e) {
          // It may happen that a request is succeeded and the onComplete has been called and pushed
          // us into the task queue, but before we get called a close is called and here we will
          // fail the request, although it is succeeded actually.
          // This is not a perfect solution but anyway, it is better than hang the requests for
          // ever, and also acceptable as if you close the zk client before actually getting the
          // response then a failure is always possible.
          future.completeExceptionally(e);
        }
      });
    }

    @Override
    public boolean needZk() {
      return true;
    }

    public boolean delay(long intervalMs, int maxRetries) {
      if (retries >= maxRetries) {
        return false;
      }
      retries++;
      time = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervalMs);
      return true;
    }

    @Override
    public void connectFailed(IOException e) {
      if (delay(retryIntervalMs, maxRetries)) {
        LOG.warn(getId() + " failed to connect to zk for " + operationType + " of " + path +
            ", retries = " + retries,
          e);
        tasks.add(this);
      } else {
        LOG.warn(getId() + " failed to connect to zk for " + operationType + " of " + path +
            ", retries = " + retries + ", give up",
          e);
        future.completeExceptionally(e);
      }
    }

    @Override
    public void closed(IOException e) {
      future.completeExceptionally(e);
    }
  }

  private static <T> CompletableFuture<T> failed(Throwable e) {
    CompletableFuture<T> future = new CompletableFuture<>();
    future.completeExceptionally(e);
    return future;
  }

  public CompletableFuture<byte[]> get(String path) {
    if (closed.get()) {
      return failed(new IOException("Client already closed"));
    }
    CompletableFuture<byte[]> future = new CompletableFuture<>();
    tasks.add(new ZKTask<byte[]>(path, future, "get") {

      @Override
      public void exec(ZooKeeper zk) {
        zk.getData(path, false,
            (rc, path, ctx, data, stat) -> onComplete(zk, rc, data, true), null);
      }
    });
    return future;
  }

  public CompletableFuture<Stat> exists(String path) {
    if (closed.get()) {
      return failed(new IOException("Client already closed"));
    }
    CompletableFuture<Stat> future = new CompletableFuture<>();
    tasks.add(new ZKTask<Stat>(path, future, "exists") {

      @Override
      public void exec(ZooKeeper zk) {
        zk.exists(path, false, (rc, path, ctx, stat) -> onComplete(zk, rc, stat, false), null);
      }
    });
    return future;
  }

  private void closeZk() {
    if (zookeeper != null) {
      try {
        zookeeper.close();
      } catch (InterruptedException e) {
      }
      zookeeper = null;
    }
  }

  private ZooKeeper getZk() throws IOException {
    // may be closed when session expired
    if (zookeeper == null || !zookeeper.getState().isAlive()) {
      zookeeper = new ZooKeeper(connectString, sessionTimeoutMs, e -> {});
      int timeout = 10000;
      try {
        // Before returning, try and ensure we are connected. Don't wait long in case
        // we are trying to connect to a cluster that is down. If we fail to connect,
        // just catch the exception and carry-on. The first usage will fail and we'll
        // cleanup.
        zookeeper = ZooKeeperHelper.ensureConnectedZooKeeper(zookeeper, timeout);
      } catch (ZooKeeperConnectionException e) {
        LOG.warn("Failed connecting after waiting " + timeout + "ms; " + zookeeper);
      }
    }
    return zookeeper;
  }

  private void run() {
    for (;;) {
      Task task;
      try {
        task = tasks.poll(keepAliveTimeMs, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        continue;
      }
      if (task == CLOSE) {
        break;
      }
      if (task == null) {
        LOG.info(getId() + " no activities for " + keepAliveTimeMs +
            " ms, close active connection. Will reconnect next time when there are new requests.");
        closeZk();
        continue;
      }
      if (!task.needZk()) {
        task.exec(null);
      } else {
        ZooKeeper zk;
        try {
          zk = getZk();
        } catch (IOException e) {
          task.connectFailed(e);
          continue;
        }
        task.exec(zk);
      }
    }
    closeZk();
    IOException error = new IOException("Client already closed");
    Arrays.stream(tasks.toArray(new Task[0])).forEach(t -> t.closed(error));
    tasks.clear();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      LOG.info("Close zookeeper connection " + getId() + " to " + connectString);
      tasks.add(CLOSE);
    }
  }

  @VisibleForTesting
  ZooKeeper getZooKeeper() {
    return zookeeper;
  }

  @VisibleForTesting
  public String getConnectString() {
    return connectString;
  }
}
