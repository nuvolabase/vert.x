/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.nodex.core;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.DefaultFileRegion;
import org.jboss.netty.channel.FileRegion;
import org.jboss.netty.channel.socket.nio.NioSocketChannelConfig;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

public class ConnectionBase {

  protected ConnectionBase(Channel channel, String contextID, Thread th) {
    this.channel = channel;
    this.contextID = contextID;
    this.th = th;
  }

  protected final Channel channel;
  protected final String contextID;
  //For sanity checks
  protected final Thread th;

  protected ExceptionHandler exceptionHandler;
  protected Runnable closedHandler;

  // Public API -------------------------------------------------------------------------------

  public void pause() {
    channel.setReadable(false);
  }

  public void resume() {
    channel.setReadable(true);
  }

  public void setWriteQueueMaxSize(int size) {
    NioSocketChannelConfig conf = (NioSocketChannelConfig) channel.getConfig();
    conf.setWriteBufferLowWaterMark(size / 2);
    conf.setWriteBufferHighWaterMark(size);
  }

  public boolean writeQueueFull() {
    return !channel.isWritable();
  }

  public void close() {
    channel.close();
  }

  // Handlers ---------------------------------------------------------------------

  public void exception(ExceptionHandler handler) {
    this.exceptionHandler = handler;
  }

  public void closed(Runnable handler) {
    this.closedHandler = handler;
  }

  // Impl ?? ----------------------------------------------------------------------------------------------

  protected String getContextID() {
    return contextID;
  }

  protected void handleException(Exception e) {
    if (exceptionHandler != null) {
      setContextID();
      try {
        exceptionHandler.onException(e);
      } catch (Throwable t) {
        handleHandlerException(t);
      }
    } else {
      handleHandlerException(e);
    }
  }

  protected void handleClosed() {
    if (closedHandler != null) {
      setContextID();
      try {
        closedHandler.run();
      } catch (Throwable t) {
        handleHandlerException(t);
      }
    }
  }

  protected void addFuture(final Runnable done, final ChannelFuture future) {
    future.addListener(new ChannelFutureListener() {
      public void operationComplete(final ChannelFuture channelFuture) throws Exception {
        setContextID();
        if (channelFuture.isSuccess()) {
          done.run();
        } else {
          Throwable err = channelFuture.getCause();
          if (exceptionHandler != null && err instanceof Exception) {
            exceptionHandler.onException((Exception) err);
          } else {
            err.printStackTrace();
          }
        }
      }
    });
  }

  protected void setContextID() {
    // Sanity check
    // All ops should always be invoked on same thread
    if (Thread.currentThread() != th) {
      throw new IllegalStateException("Invoked with wrong thread");
    }
    NodexInternal.instance.setContextID(contextID);
  }

  protected void handleHandlerException(Throwable t) {
    //We log errors otherwise they will get swallowed
    //TODO logging
    t.printStackTrace(System.err);
  }

  protected boolean isSSL() {
    return channel.getPipeline().get(SslHandler.class) != null;
  }

  protected void sendFile(File file) {
    RandomAccessFile raf;
    try {
      raf = new RandomAccessFile(file, "r");
      long fileLength = raf.length();

      // Write the content.
      ChannelFuture writeFuture;
      if (isSSL()) {
        // Cannot use zero-copy with HTTPS.
        writeFuture = channel.write(new ChunkedFile(raf, 0, fileLength, 8192));
      } else {
        // No encryption - use zero-copy.
        final FileRegion region =
            new DefaultFileRegion(raf.getChannel(), 0, fileLength);
        channel.write(region);
      }
    } catch (IOException e) {
      handleException(e);
    }
  }
}
