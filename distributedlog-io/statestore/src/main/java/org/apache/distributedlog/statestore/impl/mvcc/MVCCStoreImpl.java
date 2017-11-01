/*
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

package org.apache.distributedlog.statestore.impl.mvcc;

import static com.google.common.base.Charsets.UTF_8;
import static org.apache.distributedlog.statestore.impl.Constants.NULL_END_KEY;
import static org.apache.distributedlog.statestore.impl.Constants.NULL_START_KEY;
import static org.apache.distributedlog.statestore.impl.rocksdb.RocksConstants.BLOCK_CACHE_SIZE;
import static org.apache.distributedlog.statestore.impl.rocksdb.RocksConstants.BLOCK_SIZE;
import static org.apache.distributedlog.statestore.impl.rocksdb.RocksConstants.DEFAULT_CHECKSUM_TYPE;
import static org.apache.distributedlog.statestore.impl.rocksdb.RocksConstants.DEFAULT_COMPACTION_STYLE;
import static org.apache.distributedlog.statestore.impl.rocksdb.RocksConstants.DEFAULT_COMPRESSION_TYPE;
import static org.apache.distributedlog.statestore.impl.rocksdb.RocksConstants.DEFAULT_LOG_LEVEL;
import static org.apache.distributedlog.statestore.impl.rocksdb.RocksConstants.DEFAULT_PARALLELISM;
import static org.apache.distributedlog.statestore.impl.rocksdb.RocksConstants.MAX_WRITE_BUFFERS;
import static org.apache.distributedlog.statestore.impl.rocksdb.RocksConstants.WRITE_BUFFER_SIZE;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.PeekingIterator;
import com.google.common.primitives.SignedBytes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.mutable.MutableLong;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.distributedlog.common.coder.Coder;
import org.apache.distributedlog.statestore.api.KV;
import org.apache.distributedlog.statestore.api.KVIterator;
import org.apache.distributedlog.statestore.api.KVMulti;
import org.apache.distributedlog.statestore.api.mvcc.KVRecord;
import org.apache.distributedlog.statestore.api.mvcc.MVCCStore;
import org.apache.distributedlog.statestore.api.mvcc.op.CompareOp;
import org.apache.distributedlog.statestore.api.mvcc.op.DeleteOp;
import org.apache.distributedlog.statestore.api.mvcc.op.Op;
import org.apache.distributedlog.statestore.api.mvcc.op.OpFactory;
import org.apache.distributedlog.statestore.api.mvcc.op.PutOp;
import org.apache.distributedlog.statestore.api.mvcc.op.RangeOp;
import org.apache.distributedlog.statestore.api.mvcc.op.TxnOp;
import org.apache.distributedlog.statestore.api.mvcc.result.Code;
import org.apache.distributedlog.statestore.api.mvcc.result.DeleteResult;
import org.apache.distributedlog.statestore.api.mvcc.result.PutResult;
import org.apache.distributedlog.statestore.api.mvcc.result.RangeResult;
import org.apache.distributedlog.statestore.api.mvcc.result.Result;
import org.apache.distributedlog.statestore.api.mvcc.result.TxnResult;
import org.apache.distributedlog.statestore.exceptions.InvalidStateStoreException;
import org.apache.distributedlog.statestore.exceptions.MVCCStoreException;
import org.apache.distributedlog.statestore.exceptions.StateStoreException;
import org.apache.distributedlog.statestore.exceptions.StateStoreRuntimeException;
import org.apache.distributedlog.statestore.impl.Constants;
import org.apache.distributedlog.statestore.impl.KVImpl;
import org.apache.distributedlog.statestore.impl.mvcc.op.OpFactoryImpl;
import org.apache.distributedlog.statestore.impl.mvcc.op.RangeOpImpl;
import org.apache.distributedlog.statestore.impl.mvcc.result.DeleteResultImpl;
import org.apache.distributedlog.statestore.impl.mvcc.result.PutResultImpl;
import org.apache.distributedlog.statestore.impl.mvcc.result.RangeResultImpl;
import org.apache.distributedlog.statestore.impl.mvcc.result.ResultFactory;
import org.apache.distributedlog.statestore.impl.mvcc.result.TxnResultImpl;
import org.apache.distributedlog.statestore.impl.rocksdb.RocksUtils;
import org.apache.distributedlog.statestore.impl.rocksdb.RocksdbKVStore;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;

/**
 * MVCC Store Implementation.
 *
 * <p>The current implementation executes write operations in one single io thread.
 * It can be improved later to leverage the revision numbers to achieve mvcc.
 */
@Slf4j
class MVCCStoreImpl<K, V> extends RocksdbKVStore<K, V> implements MVCCStore<K, V> {

    private static final String DATA_CF_NAME = "default";
    private static final byte[] DATA_CF_NAME_BYTES = DATA_CF_NAME.getBytes(UTF_8);
    private static final Comparator<byte[]> COMPARATOR = SignedBytes.lexicographicalComparator();

    private final ResultFactory<K, V> resultFactory;
    private final KVRecordFactory<K, V> recordFactory;
    private final OpFactory<K, V> opFactory;
    private final Coder<MVCCRecord> recordCoder = MVCCRecordCoder.of();

    private DBOptions dbOpts;
    private ColumnFamilyOptions dataCfOpts;
    private ColumnFamilyDescriptor dataCfDesc;
    private ColumnFamilyHandle dataCfHandle;

    MVCCStoreImpl() {
        this.resultFactory = new ResultFactory<>();
        this.recordFactory = new KVRecordFactory<>();
        this.opFactory = new OpFactoryImpl<>();
    }

    @Override
    public OpFactory<K, V> getOpFactory() {
        return opFactory;
    }

    @Override
    protected RocksDB openLocalDB(File dir, Options options) throws StateStoreException {
        dbOpts = new DBOptions();
        dbOpts.setCreateIfMissing(true);
        dbOpts.setErrorIfExists(false);
        dbOpts.setInfoLogLevel(DEFAULT_LOG_LEVEL);
        dbOpts.setIncreaseParallelism(DEFAULT_PARALLELISM);

        final BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
        tableConfig.setBlockCacheSize(BLOCK_CACHE_SIZE);
        tableConfig.setBlockSize(BLOCK_SIZE);
        tableConfig.setChecksumType(DEFAULT_CHECKSUM_TYPE);
        dataCfOpts = new ColumnFamilyOptions();
        dataCfOpts.setTableFormatConfig(tableConfig);
        dataCfOpts.setWriteBufferSize(WRITE_BUFFER_SIZE);
        dataCfOpts.setCompressionType(DEFAULT_COMPRESSION_TYPE);
        dataCfOpts.setCompactionStyle(DEFAULT_COMPACTION_STYLE);
        dataCfOpts.setMaxWriteBufferNumber(MAX_WRITE_BUFFERS);

        // make sure the db directory's parent dir is created
        try {
            Files.createDirectories(dir.getParentFile().toPath());
            dataCfDesc = new ColumnFamilyDescriptor(DATA_CF_NAME_BYTES, dataCfOpts);
            final List<ColumnFamilyHandle> handles = Lists.newArrayListWithExpectedSize(1);
            RocksDB rocksDB = RocksDB.open(
                dbOpts,
                dir.getAbsolutePath(),
                Lists.newArrayList(dataCfDesc),
                handles);
            dataCfHandle = handles.get(0);
            return rocksDB;
        } catch (IOException ioe) {
            log.error("Failed to create parent directory {} for opening rocksdb", dir.getParentFile().toPath(), ioe);
            throw new StateStoreException(ioe);
        } catch (RocksDBException dbe) {
            log.error("Failed to open rocksdb at dir {}", dir.getAbsolutePath(), dbe);
            throw new StateStoreException(dbe);
        }
    }

    @Override
    protected void closeLocalDB() {
        RocksUtils.close(dataCfHandle);
        super.closeLocalDB();
        // release options
        RocksUtils.close(dataCfOpts);
        RocksUtils.close(dbOpts);
    }

    @Override
    public void put(K key, V value) {
        throw new UnsupportedOperationException("Please use #put(PutOp op) instead");
    }

    @Override
    public synchronized V putIfAbsent(K key, V value) {
        throw new UnsupportedOperationException("Please use #put(PutOp op) instead");
    }

    @Override
    public synchronized KVMulti<K, V> multi() {
        throw new UnsupportedOperationException("Please use #txn(TxnOp op) instead");
    }

    @Override
    public synchronized V delete(K key) {
        throw new UnsupportedOperationException("Please use #delete(DeleteOp op) instead");
    }

    void put(K key, V value, long revision) {
        PutOp<K, V> op = opFactory.buildPutOp()
            .key(key)
            .value(value)
            .prevKV(false)
            .revision(revision)
            .build();
        PutResult<K, V> result = null;
        try {
            result = put(op);
            if (Code.OK != result.code()) {
                throw new MVCCStoreException(result.code(),
                    "Failed to put (" + key + ", " + value + ", " + revision + ") to state store " + name);
            }
        } finally {
            if (null != result) {
                result.recycle();
            }
        }
    }

    void delete(K key, long revision) {
        DeleteOp<K, V> op = opFactory.buildDeleteOp()
            .nullableKey(key)
            .prevKV(false)
            .revision(revision)
            .build();
        DeleteResult<K, V> result = null;
        try {
            result = delete(op);
            if (Code.OK != result.code()) {
                throw new MVCCStoreException(result.code(),
                    "Failed to delete key=" + key + "from state store " + name);
            }
        } finally {
            if (null != result) {
                result.recycle();
            }
        }

    }

    void deleteRange(K key, K endKey, long revision) {
        DeleteOp<K, V> op = opFactory.buildDeleteOp()
            .nullableKey(key)
            .nullableEndKey(endKey)
            .prevKV(false)
            .revision(revision)
            .isRangeOp(true)
            .build();
        DeleteResult<K, V> result = null;
        try {
            result = delete(op);
            if (Code.OK != result.code()) {
                throw new MVCCStoreException(result.code(),
                    "Failed to delete key=" + key + "from state store " + name);
            }
        } finally {
            if (null != result) {
                result.recycle();
            }
        }

    }

    @Override
    public synchronized V get(K key) {
        RangeOp<K, V> op = opFactory.buildRangeOp()
            .nullableKey(key)
            .limit(1)
            .build();
        RangeResult<K, V> result = null;
        try {
            result = range(op);
            if (Code.OK != result.code()) {
                throw new MVCCStoreException(result.code(),
                    "Failed to retrieve key from store " + name + " : code = " + result.code());
            }
            if (result.count() <= 0) {
                return null;
            } else {
                return result.kvs().get(0).value();
            }
        } finally {
            if (null != result) {
                result.recycle();
            }
        }
    }

    @Override
    public synchronized KVIterator<K, V> range(K from, K to) {
        checkStoreOpen();

        RangeResultIterator iter = new RangeResultIterator(from, to);
        kvIters.add(iter);
        return iter;
    }

    class RangeResultIterator implements KVIterator<K, V> {

        private final K to;
        private K next;
        private RangeResult<K, V> result;
        private PeekingIterator<KVRecord<K, V>> resultIter;
        private boolean eor = false;

        private volatile boolean closed = false;

        RangeResultIterator(K from, K to) {
            this.to = to;
            this.next = from;
        }

        private void ensureIteratorOpen() {
            if (closed) {
                throw new InvalidStateStoreException("MVCC state store " + name + " is already closed.");
            }
        }

        @Override
        public void close() {
            kvIters.remove(this);
            if (null != result) {
                result.recycle();
            }
            closed = true;
        }

        private void getNextBatch() {
            RangeOp<K, V> op = opFactory.buildRangeOp()
                .nullableKey(next)
                .nullableEndKey(to)
                .isRangeOp(true)
                .limit(32)
                .build();
            this.result = range(op);
            if (Code.OK != result.code()) {
                throw new MVCCStoreException(result.code(),
                    "Failed to fetch kv pairs at range [" + next + ", " + to + "] from state store " + name);
            }
            this.resultIter = Iterators.peekingIterator(result.kvs().iterator());
        }

        private void skipFirstKey() {
            while (this.resultIter.hasNext()) {
                KVRecord<K, V> kv = this.resultIter.peek();
                if (!kv.key().equals(next)) {
                    break;
                }
                this.resultIter.next();
            }
        }

        @Override
        public boolean hasNext() {
            ensureIteratorOpen();

            if (eor) {
                return false;
            }
            if (null == result) {
                getNextBatch();
            }
            if (!this.resultIter.hasNext()) {
                if (this.result.hasMore()) {
                    this.result.recycle();
                    getNextBatch();
                    skipFirstKey();
                    return hasNext();
                } else {
                    eor = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public KV<K, V> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            KVRecord<K, V> kv = this.resultIter.next();
            next = kv.key();
            if (next.equals(to)) {
                eor = true;
            }
            return new KVImpl<>(kv.key(), kv.value());
        }
    }

    //
    // Write View
    //

    private void executeBatch(WriteBatch batch) {
        try {
            db.write(writeOpts, batch);
        } catch (RocksDBException e) {
            throw new StateStoreRuntimeException("Error while executing a multi operation from state store " + name, e);
        }
    }

    @Override
    public PutResult<K, V> put(PutOp<K, V> op) {
        return put(op.revision(), op);
    }

    synchronized PutResult<K, V> put(long revision, PutOp<K, V> op) {
        checkStoreOpen();

        WriteBatch batch = new WriteBatch();
        PutResult<K, V> result = null;
        try {
            result = put(revision, batch, op);
            executeBatch(batch);
            return result;
        } catch (StateStoreRuntimeException e) {
            if (null != result) {
                result.recycle();
            }
            throw e;
        } finally {
            RocksUtils.close(batch);
        }
    }

    private PutResult<K, V> put(long revision, WriteBatch batch, PutOp<K, V> op) {
        // parameters
        final K key = op.key();
        final V val = op.value();

        // raw key & value
        final byte[] rawKey = keyCoder.encode(key);
        final ByteBuf rawValBuf = valCoder.encodeBuf(val);

        MVCCRecord record;
        try {
            record = getKeyRecord(key, rawKey);
        } catch (StateStoreRuntimeException e) {
            rawValBuf.release();
            throw e;
        }

        // result
        final PutResultImpl<K, V> result = resultFactory.newPutResult(revision);
        MVCCRecord oldRecord = null;
        try {
            if (null != record) {
                // validate the update revision before applying the update to the record
                if (record.compareModRev(revision) >= 0) {
                    result.setCode(Code.SMALLER_REVISION);
                    return result;
                }

                if (op.prevKV()) {
                    // make a copy before modification
                    oldRecord = record.duplicate();
                }
                record.setVersion(record.getVersion() + 1);
            } else {
                record = MVCCRecord.newRecord();
                record.setCreateRev(revision);
                record.setVersion(0);
            }
            record.setValue(rawValBuf);
            record.setModRev(revision);

            // write the mvcc record back
            batch.put(dataCfHandle, rawKey, recordCoder.encode(record));

            // finalize the result
            result.setCode(Code.OK);
            if (null != oldRecord) {
                KVRecordImpl<K, V> prevKV = oldRecord.asKVRecord(
                    recordFactory,
                    key,
                    valCoder);
                result.setPrevKV(prevKV);
            }
            return result;
        } catch (StateStoreRuntimeException e) {
            result.recycle();
            throw e;
        } finally {
            if (null != record) {
                record.recycle();
            }
            if (null != oldRecord) {
                oldRecord.recycle();
            }
        }
    }

    //
    // Delete Op
    //

    @Override
    public DeleteResult<K, V> delete(DeleteOp<K, V> op) {
        return delete(op.revision(), op);
    }

    synchronized DeleteResult<K, V> delete(long revision, DeleteOp<K, V> op) {
        checkStoreOpen();

        WriteBatch batch = new WriteBatch();
        DeleteResult<K, V> result = null;
        try {
            result = delete(revision, batch, op, true);
            executeBatch(batch);
            return result;
        } catch (StateStoreRuntimeException e) {
            if (null != result) {
                result.recycle();
            }
            throw e;
        } finally {
            RocksUtils.close(batch);
        }
    }

    DeleteResult<K, V> delete(long revision, WriteBatch batch, DeleteOp<K, V> op, boolean allowBlind) {
        // parameters
        final K key = op.key().orElse(null);
        final K endKey = op.endKey().orElse(null);
        final boolean blind = allowBlind && !op.prevKV();

        final byte[] rawKey = (null != key) ? keyCoder.encode(key) : NULL_START_KEY;
        final byte[] rawEndKey = (null != endKey) ? keyCoder.encode(endKey) : (op.isRangeOp() ? NULL_END_KEY : null);

        // result
        final DeleteResultImpl<K, V> result = resultFactory.newDeleteResult(revision);
        final List<byte[]> keys = Lists.newArrayList();
        final List<MVCCRecord> records = Lists.newArrayList();
        try {
            long numDeleted;
            if (blind) {
                deleteBlind(batch, rawKey, rawEndKey);
                numDeleted = 0;
            } else {
                numDeleted = deleteUsingIter(
                    batch,
                    key,
                    rawKey,
                    rawEndKey,
                    keys,
                    records,
                    false);
            }

            List<KVRecord<K, V>> kvs = toKvs(keys, records);

            result.setCode(Code.OK);
            result.setPrevKvs(kvs);
            result.setNumDeleted(numDeleted);
        } catch (StateStoreRuntimeException e) {
            result.recycle();
            throw e;
        } finally {
            records.forEach(MVCCRecord::recycle);
        }
        return result;
    }

    void deleteBlind(WriteBatch batch,
                     byte[] key,
                     @Nullable byte[] endKey) {
        if (null == endKey) {
            batch.remove(key);
        } else {
            Pair<byte[], byte[]> realRange = getRealRange(key, endKey);
            batch.deleteRange(realRange.getLeft(), realRange.getRight());
        }
    }

    long deleteUsingIter(WriteBatch batch,
                         K key,
                         byte[] rawKey,
                         @Nullable byte[] rawEndKey,
                         List<byte[]> resultKeys,
                         List<MVCCRecord> resultValues,
                         boolean countOnly) {
        MutableLong numKvs = new MutableLong(0L);
        if (null == rawEndKey) {
            MVCCRecord record = getKeyRecord(key, rawKey);
            if (null != record) {
                if (!countOnly) {
                    resultKeys.add(rawKey);
                    resultValues.add(record);
                } else {
                    record.recycle();
                }
                numKvs.add(1L);
                batch.remove(rawKey);
            }
        } else {
            Pair<byte[], byte[]> realRange = getRealRange(rawKey, rawEndKey);
            rawKey = realRange.getLeft();
            rawEndKey = realRange.getRight();

            getKeyRecords(
                rawKey,
                rawEndKey,
                resultKeys,
                resultValues,
                numKvs,
                record -> true,
                -1,
                countOnly);

            deleteBlind(batch, rawKey, rawEndKey);
        }
        return numKvs.longValue();
    }

    //
    // Txn Op
    //

    @Override
    public TxnResult<K, V> txn(TxnOp<K, V> op) {
        return txn(op.revision(), op);
    }

    synchronized TxnResult<K, V> txn(long revision, TxnOp<K, V> op) {
        checkStoreOpen();

        // 1. process the compares
        boolean success = processCompares(op);

        // 2. prepare the response list
        List<Op<K, V>> operations;
        List<Result<K, V>> results;
        if (success) {
            operations = op.successOps();
        } else {
            operations = op.failureOps();
        }
        results = Lists.newArrayListWithExpectedSize(operations.size());

        // 3. process the operations
        try (WriteBatch batch = new WriteBatch()) {
            for (Op o : operations) {
                results.add(executeOp(revision, batch, o));
            }
            executeBatch(batch);

            // 4. repare the result
            TxnResultImpl<K, V> txnResult = resultFactory.newTxnResult(revision);
            txnResult.setSuccess(success);
            txnResult.setResults(results);
            txnResult.setCode(Code.OK);

            return txnResult;
        } catch (StateStoreRuntimeException e) {
            results.forEach(Result::recycle);
            throw e;
        }

    }

    boolean processCompareOp(CompareOp<K, V> op) {
        MVCCRecord record = null;
        K key = op.getKey();
        byte[] rawKey = keyCoder.encode(key);
        try {
            record = getKeyRecord(key, rawKey);
            if (null == record) {
                throw new MVCCStoreException(Code.KEY_NOT_FOUND, "Key " + key + " is not found");
            }
            return processCompareOp(record, op);
        } finally {
            if (null != record) {
                record.recycle();
            }
        }

    }

    boolean processCompareOp(MVCCRecord record,
                             CompareOp<K, V> op) {
        int cmp;
        switch (op.getTarget()) {
            case MOD:
                cmp = record.compareModRev(op.getRevision());
                break;
            case CREATE:
                cmp = record.compareCreateRev(op.getRevision());
                break;
            case VERSION:
                cmp = record.compareVersion(op.getRevision());
                break;
            case VALUE:
                byte[] rawValue = valCoder.encode(op.getValue());
                cmp = record.getValue().compareTo(Unpooled.wrappedBuffer(rawValue));
                break;
            default:
                return false;
        }
        boolean success;
        switch (op.getResult()) {
            case LESS:
                success = cmp < 0;
                break;
            case EQUAL:
                success = cmp == 0;
                break;
            case GREATER:
                success = cmp > 0;
                break;
            case NOT_EQUAL:
                success = cmp != 0;
                break;
            default:
                success = false;
                break;
        }
        return success;
    }

    boolean processCompares(TxnOp<K, V> op) {
        for (CompareOp<K, V> compare : op.compareOps()) {
            if (processCompareOp(compare)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private Result<K, V> executeOp(long revision, WriteBatch batch, Op<K, V> op) {
        if (op instanceof PutOp) {
            return put(revision, batch, (PutOp<K, V>) op);
        } else if (op instanceof DeleteOp) {
            return delete(revision, batch, (DeleteOp<K, V>) op, true);
        } else if (op instanceof RangeOp){
            return range((RangeOp<K, V>) op);
        } else {
            throw new MVCCStoreException(Code.ILLEGAL_OP, "Unknown operation in a transaction : " + op);
        }
    }

    //
    // Read View
    //

    private boolean getKeyRecords(byte[] rawKey,
                                  byte[] rawEndKey,
                                  List<byte[]> resultKeys,
                                  List<MVCCRecord> resultValues,
                                  MutableLong numKvs,
                                  @Nullable Predicate<MVCCRecord> predicate,
                                  int limit,
                                  boolean countOnly) {
        try (RocksIterator iter = db.newIterator(dataCfHandle)) {
            iter.seek(rawKey);
            boolean eor = false;
            while (iter.isValid() && (limit < 0 || resultKeys.size() < limit)) {
                byte[] key = iter.key();
                if (COMPARATOR.compare(rawEndKey, key) < 0) {
                    eor = true;
                    break;
                }
                MVCCRecord val = recordCoder.decode(iter.value());

                processRecord(key, val, resultKeys, resultValues, numKvs, predicate, countOnly);

                iter.next();
            }
            if (eor) {
                return false;
            } else {
                return iter.isValid();
            }
        }
    }

    private void processRecord(byte[] key,
                               MVCCRecord record,
                               List<byte[]> resultKeys,
                               List<MVCCRecord> resultValues,
                               MutableLong numKvs,
                               @Nullable Predicate<MVCCRecord> predicate,
                               boolean countOnly) {
        if (null == predicate && countOnly) {
            numKvs.increment();
            return;
        }

        if (null == predicate || predicate.test(record)) {
            numKvs.increment();
            if (countOnly) {
                record.recycle();
            } else {
                resultKeys.add(key);
                resultValues.add(record);
            }
        } else {
            record.recycle();
        }
    }

    private MVCCRecord getKeyRecord(K key, byte[] keyBytes) {
        try {
            byte[] valBytes = this.db.get(dataCfHandle, keyBytes);
            if (null == valBytes) {
                return null;
            }
            return recordCoder.decode(valBytes);
        } catch (RocksDBException e) {
            throw new StateStoreRuntimeException("Error while getting value for key "
                + key + " from state store " + name, e);
        }

    }

    @Override
    public synchronized RangeResult<K, V> range(RangeOp<K, V> rangeOp) {
        checkStoreOpen();

        // parameters
        final K key = rangeOp.key().orElse(null);
        final K endKey = rangeOp.endKey().orElse(null);
        final RangeOpImpl<K, V> rangeOpImpl = (RangeOpImpl<K, V>) rangeOp;

        // result
        final RangeResultImpl<K, V> result = resultFactory.newRangeResult(rangeOp.revision());

        // raw key
        byte[] rawKey = (null != key) ? keyCoder.encode(key) : NULL_START_KEY;

        if (null == endKey && !rangeOp.isRangeOp()) {
            // point lookup
            MVCCRecord record = getKeyRecord(key, rawKey);
            try {
                if (null == record || !rangeOpImpl.test(record)) {
                    result.setCount(0);
                    result.setKvs(Collections.emptyList());
                } else {
                    result.setCount(1);
                    result.setKvs(Lists.newArrayList(record.asKVRecord(
                        recordFactory,
                        key,
                        valCoder)));
                }
                result.setHasMore(false);
                result.setCode(Code.OK);
                return result;
            } finally {
                if (null != record) {
                    record.recycle();
                }
            }
        }
        byte[] rawEndKey = (null != endKey) ? keyCoder.encode(endKey) : NULL_END_KEY;
        Pair<byte[], byte[]> realRange = getRealRange(rawKey, rawEndKey);
        rawKey = realRange.getLeft();
        rawEndKey = realRange.getRight();

        // range lookup
        List<byte[]> keys = Lists.newArrayList();
        List<MVCCRecord> records = Lists.newArrayList();
        MutableLong numKvs = new MutableLong(0L);

        try {

            boolean hasMore = getKeyRecords(
                rawKey,
                rawEndKey,
                keys,
                records,
                numKvs,
                rangeOpImpl,
                rangeOp.limit(),
                false);

            List<KVRecord<K, V>> kvs = toKvs(keys, records);

            result.setCode(Code.OK);
            result.setKvs(kvs);
            result.setCount(kvs.size());
            result.setHasMore(hasMore);
        } finally {
            records.forEach(MVCCRecord::recycle);
        }
        return result;
    }

    private List<KVRecord<K, V>> toKvs(List<byte[]> keys, List<MVCCRecord> records) {
        List<KVRecord<K, V>> kvs = Lists.newArrayListWithExpectedSize(keys.size());

        for (int i = 0; i < keys.size(); i++) {
            byte[] keyBytes = keys.get(i);
            MVCCRecord record = records.get(i);
            kvs.add(record.asKVRecord(
                recordFactory,
                keyCoder.decode(keyBytes),
                valCoder
            ));
        }
        return kvs;
    }

    private Pair<byte[], byte[]> getRealRange(byte[] rawKey, byte[] rawEndKey) {
        boolean isNullStartKey = Constants.isNullStartKey(rawKey);
        boolean isNullEndKey = Constants.isNullEndKey(rawEndKey);
        if (isNullStartKey || isNullEndKey) {
            try (RocksIterator iter = db.newIterator(dataCfHandle)) {
                if (isNullStartKey) {
                    iter.seekToFirst();
                    if (!iter.isValid()) {
                        // no key to delete
                        return null;
                    }
                    rawKey = iter.key();
                }
                if (isNullEndKey) {
                    iter.seekToLast();
                    if (!iter.isValid()) {
                        // no key to delete
                        return null;
                    }
                    rawEndKey = iter.key();
                }
            }
        }
        ++rawEndKey[rawEndKey.length - 1];
        return Pair.of(rawKey, rawEndKey);
    }
}