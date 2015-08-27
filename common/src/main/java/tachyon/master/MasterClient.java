/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.master;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;

import tachyon.Constants;
import tachyon.HeartbeatExecutor;
import tachyon.HeartbeatThread;
import tachyon.LeaderInquireClient;
import tachyon.TachyonURI;
import tachyon.Version;
import tachyon.conf.TachyonConf;
import tachyon.retry.ExponentialBackoffRetry;
import tachyon.retry.RetryPolicy;
import tachyon.security.authentication.AuthenticationFactory;
import tachyon.thrift.BlockInfoException;
import tachyon.thrift.ClientBlockInfo;
import tachyon.thrift.ClientDependencyInfo;
import tachyon.thrift.ClientFileInfo;
import tachyon.thrift.ClientRawTableInfo;
import tachyon.thrift.ClientWorkerInfo;
import tachyon.thrift.Command;
import tachyon.thrift.DependencyDoesNotExistException;
import tachyon.thrift.FileAlreadyExistException;
import tachyon.thrift.FileDoesNotExistException;
import tachyon.thrift.InvalidPathException;
import tachyon.thrift.MasterService;
import tachyon.thrift.NetAddress;
import tachyon.thrift.NoWorkerException;
import tachyon.thrift.SuspectedFileSizeException;
import tachyon.thrift.TableColumnException;
import tachyon.thrift.TableDoesNotExistException;
import tachyon.thrift.TachyonException;
import tachyon.util.io.BufferUtils;
import tachyon.util.network.NetworkAddressUtils;

/**
 * The client side of master server.
 *
 * Since MasterService.Client is not thread safe, this class is a wrapper of MasterService.Client to
 * provide thread safety.
 */
// TODO When TException happens, the caller can't really do anything about it.
// when the other exceptions are thrown as a IOException, the caller can't do anything about it
// so all exceptions are handled poorly. This logic needs to be redone and be consistent.
public final class MasterClient implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  private final boolean mUseZookeeper;
  private MasterService.Client mClient = null;
  private InetSocketAddress mMasterAddress = null;
  private TProtocol mProtocol = null;
  private volatile boolean mConnected;
  private volatile boolean mIsClosed;
  /** A unique Id generated by master to identify a client */
  private volatile long mUserId = -1;
  private final ExecutorService mExecutorService;
  private Future<?> mHeartbeat;
  private final TachyonConf mTachyonConf;

  public MasterClient(InetSocketAddress masterAddress, ExecutorService executorService,
      TachyonConf tachyonConf) {
    mTachyonConf = tachyonConf;
    mUseZookeeper = mTachyonConf.getBoolean(Constants.USE_ZOOKEEPER);
    if (!mUseZookeeper) {
      mMasterAddress = masterAddress;
    }
    mConnected = false;
    mIsClosed = false;
    mExecutorService = executorService;
  }

  /**
   * Add a checkpoint.
   *
   * @param workerId if -1, means the checkpoint is added directly by the client from underlayer fs.
   * @param fileId The file to add the checkpoint.
   * @param length The length of the checkpoint.
   * @param checkpointPath The path of the checkpoint.
   * @return true if checkpoint is added for the <code>fileId</code> and false otherwise
   * @throws IOException if the file does not exist, has the wrong size, or its block information is
   *         corrupted.
   */
  public synchronized boolean addCheckpoint(long workerId, int fileId, long length,
      String checkpointPath) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.addCheckpoint(workerId, fileId, length, checkpointPath);
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (SuspectedFileSizeException e) {
        throw new IOException(e);
      } catch (BlockInfoException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return false;
  }

  /**
   * Close the connection with the Tachyon Master and do the necessary cleanup. It should be used if
   * the client has not connected with the master for a while, for example.
   */
  public synchronized void disconnect() {
    if (mConnected) {
      LOG.debug("Disconnecting from the master {}", mMasterAddress);
      mConnected = false;
    }
    try {
      if (mProtocol != null) {
        mProtocol.getTransport().close();
      }
    } finally {
      if (mHeartbeat != null) {
        mHeartbeat.cancel(true);
      }
    }
  }

  /**
   * Connect with the Tachyon Master.
   *
   * @throws IOException if the connection fails.
   */
  public synchronized void connect() throws IOException {
    if (mConnected) {
      return;
    }

    disconnect();

    if (mIsClosed) {
      throw new IOException("Client is closed, will not try to connect");
    }

    Exception lastException = null;
    int maxConnectsTry = mTachyonConf.getInt(Constants.MASTER_RETRY_COUNT);
    RetryPolicy retry = new ExponentialBackoffRetry(50, Constants.SECOND_MS, maxConnectsTry);
    do {
      mMasterAddress = getMasterAddress();

      LOG.info("Tachyon client (version " + Version.VERSION + ") is trying to connect with master"
          + " @ " + mMasterAddress);

      AuthenticationFactory factory = new AuthenticationFactory(mTachyonConf);
      mProtocol = new TBinaryProtocol(factory.getClientTransport(mMasterAddress));
      mClient = new MasterService.Client(mProtocol);
      try {
        mProtocol.getTransport().open();

        HeartbeatExecutor heartBeater = new MasterClientHeartbeatExecutor(this);

        String threadName = "master-heartbeat-" + mMasterAddress;
        int interval =
            mTachyonConf.getInt(Constants.USER_HEARTBEAT_INTERVAL_MS);
        mHeartbeat =
            mExecutorService.submit(new HeartbeatThread(threadName, heartBeater, interval / 2));
      } catch (TTransportException e) {
        lastException = e;
        LOG.error("Failed to connect (" + retry.getRetryCount() + ") with master @ "
            + mMasterAddress + " : " + e.getMessage());
        if (mHeartbeat != null) {
          mHeartbeat.cancel(true);
        }
        continue;
      }

      try {
        mUserId = mClient.user_getUserId();
      } catch (TException e) {
        lastException = e;
        LOG.error(e.getMessage(), e);
        continue;
      }
      LOG.info("User registered with the master @ " + mMasterAddress + "; got UserId " + mUserId);

      mConnected = true;
      return;
    } while (retry.attemptRetry() && !mIsClosed);

    // Reaching here indicates that we did not successfully connect.
    throw new IOException("Failed to connect with master @ " + mMasterAddress + " after "
        + (retry.getRetryCount()) + " attempts", lastException);
  }

  /**
   * Reset the connection with the Tachyon Master.
   * <p>
   * Mainly used for worker to master syncer (e.g., BlockMasterSyncer, PinListSyncer, etc.)  to
   * recover from Exception while running in case the master changes.
   */
  public synchronized void resetConnection() {
    disconnect();
    try {
      connect();
    } catch (IOException ioe) {
      LOG.error("Failed to reset the connection with tachyon master.", ioe);
    }
  }

  /**
   * Get the client dependency info from master server.
   *
   * @param depId Dependency id
   * @return ClientDependencyInfo returned from master
   * @throws IOException if the dependency does not exist.
   */
  public synchronized ClientDependencyInfo getClientDependencyInfo(int depId) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_getClientDependencyInfo(depId);
      } catch (DependencyDoesNotExistException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Get the file status from master server. If fileId is not -1, check the file status by its
   * fileId, otherwise check the file status by path.
   *
   * @param fileId The id of the file
   * @param path The path of the file
   * @return ClientFileInfo returned from master
   * @throws IOException if the path is invalid.
   */
  public synchronized ClientFileInfo getFileStatus(int fileId, String path) throws IOException {
    if (path == null) {
      path = "";
    }
    if (fileId == -1 && !path.startsWith(TachyonURI.SEPARATOR)) {
      throw new IOException("Illegal path parameter: " + path);
    }

    while (!mIsClosed) {
      connect();

      try {
        return mClient.getFileStatus(fileId, path);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Get the master address.
   *
   * @return InetSocketAddress storing master address
   */
  private synchronized InetSocketAddress getMasterAddress() {
    if (!mUseZookeeper) {
      return mMasterAddress;
    }

    LeaderInquireClient leaderInquireClient =
        LeaderInquireClient.getClient(mTachyonConf.get(Constants.ZOOKEEPER_ADDRESS),
            mTachyonConf.get(Constants.ZOOKEEPER_LEADER_PATH));
    try {
      String temp = leaderInquireClient.getMasterAddress();
      return NetworkAddressUtils.parseInetSocketAddress(temp);
    } catch (IOException ioe) {
      LOG.error(ioe.getMessage(), ioe);
      throw Throwables.propagate(ioe);
    }
  }

  /**
   * Get the id of this master client.
   *
   * @return the id of this client
   * @throws IOException if the connection fails
   */
  public synchronized long getUserId() throws IOException {
    if (mIsClosed) {
      return -1;
    }
    connect();
    return mUserId;
  }

  /**
   * Get the info of a list of workers.
   *
   * @return A list of worker info returned by master
   * @throws IOException if the connection fails
   */
  public synchronized List<ClientWorkerInfo> getWorkersInfo() throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.getWorkersInfo();
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Get the total capacity in bytes.
   *
   * @return capacity in bytes
   * @throws IOException if the connection fails
   */
  public synchronized long getCapacityBytes() throws IOException {
    while (!mIsClosed) {
      connect();
      try {
        return mClient.user_getCapacityBytes();
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return -1;
  }

  /**
   * Get the amount of used space in bytes.
   *
   * @return amount of used space in bytes
   * @throws IOException if the connection fails
   */
  public synchronized long getUsedBytes() throws IOException {
    while (!mIsClosed) {
      connect();
      try {
        return mClient.user_getUsedBytes();
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return -1;
  }

  /**
   * Return a connection status.
   *
   * @return connection status
   */
  public synchronized boolean isConnected() {
    return mConnected;
  }

  /**
   * If the <code>path</code> is a directory, return all the direct entries in it. If the
   * <code>path</code> is a file, return its ClientFileInfo.
   *
   * @param path the target directory/file path
   * @return A list of ClientFileInfo, null if the file or folder does not exist.
   * @throws IOException if the path is invalid or points to a non-existing object.
   */
  public synchronized List<ClientFileInfo> listStatus(String path) throws IOException {
    while (!mIsClosed) {
      connect();
      try {
        return mClient.liststatus(path);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Check parameters.
   *
   * @param id to check
   * @param path to check
   * @throws IOException if the path is null or contains an illegal parameter
   */
  private synchronized void parameterCheck(int id, String path) throws IOException {
    if (path == null) {
      throw new NullPointerException("Paths may not be null; empty is the null state");
    }
    if (id == -1 && !path.startsWith(TachyonURI.SEPARATOR)) {
      throw new IOException("Illegal path parameter: " + path);
    }
  }

  /**
   * Close the connection with the Tachyon Master permanently. MasterClient instance should be
   * discarded after this is executed.
   */
  @Override
  public synchronized void close() {
    disconnect();
    mIsClosed = true;
  }

  /**
   * The file is complete.
   *
   * @param fileId the file id
   * @throws IOException if the file does not exist
   */
  public synchronized void user_completeFile(int fileId) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        mClient.user_completeFile(fileId);
        return;
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
  }

  /**
   * Create a dependency.
   *
   * @param parents the dependency's input files
   * @param children the dependency's output files
   * @param commandPrefix identifies a command prefix
   * @param data stores dependency data
   * @param comment records a dependency comment
   * @param framework identifies the framework
   * @param frameworkVersion identifies the framework version
   * @param dependencyType the dependency's type, Wide or Narrow
   * @param childrenBlockSizeByte the block size of the dependency's output files
   * @return the dependency's id
   * @throws IOException if an event that prevents the dependency from being created is encountered.
   */
  public synchronized int user_createDependency(List<String> parents, List<String> children,
      String commandPrefix, List<ByteBuffer> data, String comment, String framework,
      String frameworkVersion, int dependencyType, long childrenBlockSizeByte) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_createDependency(parents, children, commandPrefix, data, comment,
            framework, frameworkVersion, dependencyType, childrenBlockSizeByte);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (FileAlreadyExistException e) {
        throw new IOException(e);
      } catch (BlockInfoException e) {
        throw new IOException(e);
      } catch (TachyonException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return -1;
  }

  /**
   * Create a new file in the file system.
   *
   * @param path The path of the file
   * @param ufsPath The path of the file in the under file system. If this is empty, the file does
   *        not exist in the under file system yet.
   * @param blockSizeByte The size of the block in bytes. It is -1 iff ufsPath is non-empty.
   * @param recursive Creates necessary parent folders if true, not otherwise.
   * @return The file id, which is globally unique.
   * @throws IOException if an event that prevents the file from being created is encountered.
   */
  public synchronized int user_createFile(String path, String ufsPath, long blockSizeByte,
      boolean recursive) throws IOException {
    if (path == null || !path.startsWith(TachyonURI.SEPARATOR)) {
      throw new IOException("Illegal path parameter: " + path);
    }
    if (ufsPath == null) {
      ufsPath = "";
    }

    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_createFile(path, ufsPath, blockSizeByte, recursive);
      } catch (FileAlreadyExistException e) {
        throw new IOException(e);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (BlockInfoException e) {
        throw new IOException(e);
      } catch (SuspectedFileSizeException e) {
        throw new IOException(e);
      } catch (TachyonException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return -1;
  }

  /**
   * Create a new block for the given file.
   *
   * @param fileId The id of the file
   * @return the block id.
   * @throws IOException if the file does not exist.
   */
  public synchronized long user_createNewBlock(int fileId) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_createNewBlock(fileId);
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return -1;
  }

  /**
   * Create a RawTable and return its id.
   *
   * @param path the RawTable's path
   * @param columns number of columns it has
   * @param metadata the meta data of the RawTable
   * @return the id if succeed, -1 otherwise
   * @throws IOException If an event that prevents the table from being created is encountered.
   */
  public synchronized int user_createRawTable(String path, int columns, ByteBuffer metadata)
      throws IOException {
    if (metadata == null) {
      metadata = ByteBuffer.allocate(0);
    }

    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_createRawTable(path, columns, metadata);
      } catch (FileAlreadyExistException e) {
        throw new IOException(e);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (TableColumnException e) {
        throw new IOException(e);
      } catch (TachyonException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return -1;
  }

  /**
   * Delete a file or folder.
   *
   * @param fileId The id of the file / folder. If it is not -1, path parameter is ignored.
   *        Otherwise, the method uses the path parameter.
   * @param path The path of the file / folder. It could be empty iff id is not -1.
   * @param recursive If fileId or path represents a non-empty folder, delete the folder recursively
   *        or not
   * @return true if deletes successfully, false otherwise.
   * @throws IOException if an event that prevent the file / folder from being deleted is
   *         encountered.
   */
  public synchronized boolean user_delete(int fileId, String path, boolean recursive)
      throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_delete(fileId, path, recursive);
      } catch (TachyonException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return false;
  }

  /**
   * Get the block id by the file id and block index. It will check whether the file and the block
   * exist.
   *
   * @param fileId the file id
   * @param blockIndex The index of the block in the file.
   * @return the block id if exists
   * @throws IOException if the file does not exist, or connection issue.
   */
  public synchronized long user_getBlockId(int fileId, int blockIndex) throws IOException {
    while (!mIsClosed) {
      connect();
      try {
        return mClient.user_getBlockId(fileId, blockIndex);
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return -1;
  }

  /**
   * Get a ClientBlockInfo by blockId.
   *
   * @param blockId the id of the block
   * @return the ClientBlockInfo of the specified block
   * @throws IOException if the file does not exist or its block information is corrupted.
   */
  public synchronized ClientBlockInfo user_getClientBlockInfo(long blockId) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_getClientBlockInfo(blockId);
      } catch (FileDoesNotExistException e) {
        throw new FileNotFoundException(e.getMessage());
      } catch (BlockInfoException e) {
        throw new IOException(e.getMessage(), e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Get the raw table info associated with the given id and / or path.
   *
   * @param path The path of the table
   * @param id The id of the table
   * @return the table info
   * @throws IOException if the table does not exist or the path is invalid.
   */
  public synchronized ClientRawTableInfo user_getClientRawTableInfo(int id, String path)
      throws IOException {
    parameterCheck(id, path);

    while (!mIsClosed) {
      connect();

      try {
        ClientRawTableInfo ret = mClient.user_getClientRawTableInfo(id, path);
        ret.setMetadata(BufferUtils.generateNewByteBufferFromThriftRPCResults(ret.metadata));
        return ret;
      } catch (TableDoesNotExistException e) {
        throw new IOException(e);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Get the block infos of a file with the given id or path. Throws an exception if the id names a
   * directory.
   *
   * @param fileId The id of the file to look up
   * @param path The path of the file to look up
   * @return the block infos of the file
   * @throws IOException if the file does not exist or the path is invalid.
   */
  public synchronized List<ClientBlockInfo> user_getFileBlocks(int fileId, String path)
      throws IOException {
    parameterCheck(fileId, path);

    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_getFileBlocks(fileId, path);
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Get the id of the table at the given path.
   *
   * @param path The path of the table
   * @return the id of the table
   * @throws IOException if the path is invalid.
   */
  public synchronized int user_getRawTableId(String path) throws IOException {
    while (!mIsClosed) {
      connect();
      try {
        return mClient.user_getRawTableId(path);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return -1;
  }


  /**
   * Get the address of the under FS.
   *
   * @return the address of the under FS
   * @throws IOException if the connection fails
   */
  public synchronized String user_getUfsAddress() throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_getUfsAddress();
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Get the address of a worker.
   *
   * @param random If true, select a random worker
   * @param hostname If <code>random</code> is false, select a worker on this host
   * @return the address of the selected worker, or null if no address could be found
   * @throws NoWorkerException if there is no available worker
   * @throws IOException if the connection fails
   */
  public synchronized NetAddress user_getWorker(boolean random, String hostname)
      throws NoWorkerException, IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_getWorker(random, hostname);
      } catch (NoWorkerException e) {
        throw e;
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Heartbeat.
   *
   * @throws IOException if the connection fails
   */
  public synchronized void user_heartbeat() throws IOException {
    while (!mIsClosed) {
      connect();
      try {
        mClient.user_heartbeat();
        return;
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
  }

  /**
   * Create a folder.
   *
   * @param path the path of the folder to be created
   * @param recursive Creates necessary parent folders if true, not otherwise.
   * @return true if the folder is created successfully or already existing. false otherwise.
   * @throws IOException if an event that prevents the folder from being created is encountered.
   */
  public synchronized boolean user_mkdirs(String path, boolean recursive) throws IOException {
    while (!mIsClosed) {
      connect();
      try {
        return mClient.user_mkdirs(path, recursive);
      } catch (FileAlreadyExistException e) {
        throw new IOException(e);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (TachyonException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return false;
  }

  /**
   * Rename a file or folder to the indicated new path.
   *
   * @param fileId The id of the source file / folder. If it is not -1, path parameter is ignored.
   *        Otherwise, the method uses the srcPath parameter.
   * @param srcPath The path of the source file / folder. It could be empty iff id is not -1.
   * @param dstPath The path of the destination file / folder. It could be empty iff id is not -1.
   * @return true if renames successfully, false otherwise.
   * @throws IOException if an event that prevents the file / folder from being renamed is
   *         encountered.
   */
  public synchronized boolean user_rename(int fileId, String srcPath, String dstPath)
      throws IOException {
    parameterCheck(fileId, srcPath);

    while (!mIsClosed) {
      connect();

      try {
        return mClient.user_rename(fileId, srcPath, dstPath);
      } catch (FileAlreadyExistException e) {
        throw new IOException(e);
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (InvalidPathException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return false;
  }

  /**
   * Report the lost file to master.
   *
   * @param fileId the lost file id
   * @throws IOException if the file does not exist.
   */
  public synchronized void user_reportLostFile(int fileId) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        mClient.user_reportLostFile(fileId);
        return;
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
  }

  /**
   * Request the dependency's needed files.
   *
   * @param depId the dependency id
   * @throws IOException if the dependency does not exist.
   */
  public synchronized void user_requestFilesInDependency(int depId) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        mClient.user_requestFilesInDependency(depId);
        return;
      } catch (DependencyDoesNotExistException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
  }

  /**
   * Sets the "pinned" flag for the given file. Pinned files are never evicted by Tachyon until they
   * are unpinned.
   *
   * Calling setPinned() on a folder will recursively set the "pinned" flag on all of that folder's
   * children. This may be an expensive operation for folders with many files/subfolders.
   *
   * @param id id of the file
   * @param pinned value to set
   * @throws IOException if the file does not exist.
   */
  public synchronized void user_setPinned(int id, boolean pinned) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        mClient.user_setPinned(id, pinned);
        return;
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
  }

  /**
   * Update the RawTable's meta data.
   *
   * @param id the raw table's id
   * @param metadata the new meta data
   * @throws IOException if an event that prevents the metadata from being updated is encountered.
   */
  public synchronized void user_updateRawTableMetadata(int id, ByteBuffer metadata)
      throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        mClient.user_updateRawTableMetadata(id, metadata);
        return;
      } catch (TableDoesNotExistException e) {
        throw new IOException(e);
      } catch (TachyonException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
  }

  /**
   * Frees an in-memory file or folder.
   *
   * @param fileId The id of the file / folder. If it is not -1, path parameter is ignored.
   *        Otherwise, the method uses the path parameter.
   * @param path The path of the file / folder. It could be empty iff id is not -1.
   * @param recursive If fileId or path represents a non-empty folder, free the folder recursively
   *        or not
   * @return true if in-memory free successfully, false otherwise.
   * @throws IOException if the file / folder does not exist.
   */
  public synchronized boolean user_freepath(int fileId, String path, boolean recursive)
      throws IOException {
    while (!mIsClosed) {
      connect();
      try {
        return mClient.user_freepath(fileId, path, recursive);
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return false;
  }

  /**
   * Cache a block in worker's memory.
   *
   * @param workerId the id of the worker
   * @param usedBytesOnTier used bytes on certain storage tier
   * @param storageDirId the id of the storage directory
   * @param blockId the id of the block
   * @param length the length of the block
   * @throws IOException an exception is thrown if an event that prevents the worker from caching
   *         the block is encountered.
   */
  public synchronized void worker_cacheBlock(long workerId, long usedBytesOnTier, long storageDirId,
      long blockId, long length) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        mClient.worker_cacheBlock(workerId, usedBytesOnTier, storageDirId, blockId, length);
        return;
      } catch (FileDoesNotExistException e) {
        throw new IOException(e);
      } catch (BlockInfoException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
  }

  /**
   * Get a list of the pin id's.
   *
   * @return a list of pin id's
   * @throws IOException if the connection fails
   */
  public synchronized Set<Integer> worker_getPinIdList() throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.worker_getPinIdList();
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Creates a list of high priority dependencies, which don't yet have checkpoints.
   *
   * @return the list of dependency ids
   * @throws IOException if the connection fails
   */
  public synchronized List<Integer> worker_getPriorityDependencyList() throws IOException {
    while (!mIsClosed) {
      connect();
      try {
        return mClient.worker_getPriorityDependencyList();
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return new ArrayList<Integer>();
  }

  /**
   * The heartbeat of the worker. It updates the information of the worker and removes the given
   * block id's.
   *
   * @param workerId The id of the worker to deal with
   * @param usedBytesOnTiers Used bytes on each storage tier
   * @param removedBlockIds The list of removed block ids
   * @param addedBlockIds Mapping from id of the StorageDir and id list of blocks evicted in
   * @return a command specifying an action to take
   * @throws IOException if corrupted block information is encountered.
   */
  public synchronized Command worker_heartbeat(long workerId, List<Long> usedBytesOnTiers,
      List<Long> removedBlockIds, Map<Long, List<Long>> addedBlockIds) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        return mClient.worker_heartbeat(workerId, usedBytesOnTiers, removedBlockIds, addedBlockIds);
      } catch (BlockInfoException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return null;
  }

  /**
   * Register the worker to the master.
   *
   * @param workerNetAddress Worker's NetAddress
   * @param totalBytesOnTiers Total bytes on each storage tier
   * @param usedBytesOnTiers Used bytes on each storage tier
   * @param currentBlockList Blocks in worker's space
   * @return the worker id assigned by the master
   * @throws IOException if corrupted block information is encountered.
   */
  public synchronized long worker_register(NetAddress workerNetAddress,
      List<Long> totalBytesOnTiers, List<Long> usedBytesOnTiers,
      Map<Long, List<Long>> currentBlockList) throws IOException {
    while (!mIsClosed) {
      connect();

      try {
        long ret =
            mClient.worker_register(workerNetAddress, totalBytesOnTiers, usedBytesOnTiers,
                currentBlockList);
        LOG.info("Registered at the master " + mMasterAddress + " from worker " + workerNetAddress
            + " , got WorkerId " + ret);
        return ret;
      } catch (BlockInfoException e) {
        throw new IOException(e);
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        mConnected = false;
      }
    }
    return -1;
  }
}
