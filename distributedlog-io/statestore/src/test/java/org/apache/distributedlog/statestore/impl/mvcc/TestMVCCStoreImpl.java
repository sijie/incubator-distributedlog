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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.io.Files;
import java.io.File;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.distributedlog.common.coder.StringUtf8Coder;
import org.apache.distributedlog.statestore.api.KV;
import org.apache.distributedlog.statestore.api.KVIterator;
import org.apache.distributedlog.statestore.api.StateStoreSpec;
import org.apache.distributedlog.statestore.api.mvcc.KVRecord;
import org.apache.distributedlog.statestore.api.mvcc.op.CompareResult;
import org.apache.distributedlog.statestore.api.mvcc.op.OpType;
import org.apache.distributedlog.statestore.api.mvcc.op.RangeOp;
import org.apache.distributedlog.statestore.api.mvcc.op.TxnOp;
import org.apache.distributedlog.statestore.api.mvcc.result.Code;
import org.apache.distributedlog.statestore.api.mvcc.result.DeleteResult;
import org.apache.distributedlog.statestore.api.mvcc.result.PutResult;
import org.apache.distributedlog.statestore.api.mvcc.result.RangeResult;
import org.apache.distributedlog.statestore.api.mvcc.result.Result;
import org.apache.distributedlog.statestore.api.mvcc.result.TxnResult;
import org.apache.distributedlog.statestore.exceptions.MVCCStoreException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Unit test of {@link MVCCStoreImpl}.
 */
@Slf4j
public class TestMVCCStoreImpl {

    @Rule
    public final TestName runtime = new TestName();

    private File tempDir;
    private StateStoreSpec spec;
    private MVCCStoreImpl<String, String> store;

    @Before
    public void setUp() {
        tempDir = Files.createTempDir();
        spec = StateStoreSpec.newBuilder()
            .name(runtime.getMethodName())
            .keyCoder(StringUtf8Coder.of())
            .valCoder(StringUtf8Coder.of())
            .localStateStoreDir(tempDir)
            .stream(runtime.getMethodName())
            .build();
        store = new MVCCStoreImpl<>();
    }

    @After
    public void tearDown() throws Exception {
        if (null != store) {
            store.close();
        }
        if (null != tempDir) {
            FileUtils.deleteDirectory(tempDir);
        }
    }

    //
    // Operations
    //



    @Test
    public void testGetNull() throws Exception {
        store.init(spec);
        assertNull(store.get("key"));
    }

    @Test
    public void testGetValue() throws Exception {
        store.init(spec);
        store.put("key", "value", 1L);
        assertEquals("value", store.get("key"));
    }

    @Test
    public void testPutValueSmallerRevision() throws Exception {
        store.init(spec);
        store.put("key", "value", 2L);
        assertEquals("value", store.get("key"));
        try {
            store.put("key", "newValue", 1L);
            fail("Should fail to put a value with smaller revision");
        } catch (MVCCStoreException e) {
            assertEquals(Code.SMALLER_REVISION, e.getCode());
        }
    }

    private String getKey(int i) {
        return String.format("key-%05d", i);
    }

    private String getValue(int i) {
        return String.format("value-%05d", i);
    }

    private void writeKVs(int numPairs, long revision) {
        for (int i = 0; i < numPairs; i++) {
            store.put(getKey(i), getValue(i), revision);
        }
    }

    @Test
    public void testAllRange() throws Exception {
        store.init(spec);
        writeKVs(100, 1L);
        KVIterator<String, String> iter = store.range(
            getKey(0),
            getKey(100));
        int idx = 0;
        while (iter.hasNext()) {
            KV<String, String> kv = iter.next();
            assertEquals(getKey(idx), kv.key());
            assertEquals(getValue(idx), kv.value());
            ++idx;
        }
        assertEquals(100, idx);
        iter.close();
    }

    @Test
    public void testSubRange() throws Exception {
        store.init(spec);
        writeKVs(100, 1L);
        KVIterator<String, String> iter = store.range(
            getKey(20),
            getKey(79));
        int idx = 20;
        while (iter.hasNext()) {
            KV<String, String> kv = iter.next();
            assertEquals(getKey(idx), kv.key());
            assertEquals(getValue(idx), kv.value());
            ++idx;
        }
        assertEquals(80, idx);
        iter.close();
    }

    @Test
    public void testRangeOp() throws Exception {
        store.init(spec);
        long revision = 99L;
        writeKVs(100, revision);
        RangeOp<String, String> op = store.getOpFactory().buildRangeOp()
            .key(getKey(20))
            .endKey(getKey(79))
            .limit(100)
            .build();
        RangeResult<String, String> result = store.range(op);
        assertEquals(Code.OK, result.code());
        assertEquals(60, result.count());
        assertEquals(60, result.kvs().size());
        assertEquals(false, result.hasMore());
        int idx = 20;
        for (KVRecord<String, String> record : result.kvs()) {
            assertEquals(getKey(idx), record.key());
            assertEquals(getValue(idx), record.value());
            assertEquals(revision, record.createRevision());
            assertEquals(revision, record.modifiedRevision());
            assertEquals(0, record.version());
            ++idx;
        }
        assertEquals(80, idx);
        result.recycle();
    }

    @Test
    public void testRangeOpNoMoreKvs() throws Exception {
        store.init(spec);
        long revision = 99L;
        writeKVs(100, revision);
        RangeOp<String, String> op = store.getOpFactory().buildRangeOp()
            .key(getKey(20))
            .endKey(getKey(99))
            .limit(100)
            .build();
        RangeResult<String, String> result = store.range(op);
        assertEquals(Code.OK, result.code());
        assertEquals(80, result.count());
        assertEquals(80, result.kvs().size());
        assertEquals(false, result.hasMore());
        int idx = 20;
        for (KVRecord<String, String> record : result.kvs()) {
            assertEquals(getKey(idx), record.key());
            assertEquals(getValue(idx), record.value());
            assertEquals(revision, record.createRevision());
            assertEquals(revision, record.modifiedRevision());
            assertEquals(0, record.version());
            ++idx;
        }
        assertEquals(100, idx);
        result.recycle();
    }

    @Test
    public void testRangeOpHasMoreKvs() throws Exception {
        store.init(spec);
        long revision = 99L;
        writeKVs(100, revision);
        RangeOp<String, String> op = store.getOpFactory().buildRangeOp()
            .key(getKey(20))
            .endKey(getKey(79))
            .limit(20)
            .build();
        RangeResult<String, String> result = store.range(op);
        assertEquals(Code.OK, result.code());
        assertEquals(20, result.count());
        assertEquals(20, result.kvs().size());
        assertEquals(true, result.hasMore());
        int idx = 20;
        for (KVRecord<String, String> record : result.kvs()) {
            assertEquals(getKey(idx), record.key());
            assertEquals(getValue(idx), record.value());
            assertEquals(revision, record.createRevision());
            assertEquals(revision, record.modifiedRevision());
            assertEquals(0, record.version());
            ++idx;
        }
        assertEquals(40, idx);
        result.recycle();
    }

    @Test
    public void testDeleteKey() throws Exception {
        store.init(spec);
        store.put("key", "value", 99L);
        assertEquals("value", store.get("key"));
        store.delete("key", 100L);
        assertNull(store.get("key"));
    }

    @Test
    public void testDeleteHeadRange() throws Exception {
        store.init(spec);
        // write 100 kvs
        writeKVs(100, 99L);
        // iterate all kvs
        KVIterator<String, String> iter = store.range(
            null, null);
        int idx = 0;
        while (iter.hasNext()) {
            KV<String, String> kv = iter.next();
            assertEquals(getKey(idx), kv.key());
            assertEquals(getValue(idx), kv.value());
            ++idx;
        }
        assertEquals(100, idx);
        iter.close();
        // delete range
        store.deleteRange(null, getKey(20), 100L);
        iter = store.range(
            null, null);
        idx = 21;
        while (iter.hasNext()) {
            KV<String, String> kv = iter.next();
            assertEquals(getKey(idx), kv.key());
            assertEquals(getValue(idx), kv.value());
            ++idx;
        }
        assertEquals(100, idx);
        iter.close();
    }

    @Test
    public void testDeleteTailRange() throws Exception {
        store.init(spec);
        // write 100 kvs
        writeKVs(100, 99L);
        // iterate all kvs
        KVIterator<String, String> iter = store.range(
            null, null);
        int idx = 0;
        while (iter.hasNext()) {
            KV<String, String> kv = iter.next();
            assertEquals(getKey(idx), kv.key());
            assertEquals(getValue(idx), kv.value());
            ++idx;
        }
        assertEquals(100, idx);
        iter.close();
        // delete range
        store.deleteRange(getKey(10), null, 100L);
        iter = store.range(
            null, null);
        idx = 0;
        while (iter.hasNext()) {
            KV<String, String> kv = iter.next();
            assertEquals(getKey(idx), kv.key());
            assertEquals(getValue(idx), kv.value());
            ++idx;
        }
        assertEquals(10, idx);
        iter.close();
    }

    @Test
    public void testDeleteRange() throws Exception {
        store.init(spec);
        // write 100 kvs
        writeKVs(100, 99L);
        // iterate all kvs
        KVIterator<String, String> iter = store.range(
            getKey(0),
            getKey(100));
        int idx = 0;
        while (iter.hasNext()) {
            KV<String, String> kv = iter.next();
            assertEquals(getKey(idx), kv.key());
            assertEquals(getValue(idx), kv.value());
            ++idx;
        }
        assertEquals(100, idx);
        iter.close();
        // delete range
        store.deleteRange(getKey(10), getKey(20), 100L);
        iter = store.range(
            getKey(0),
            getKey(100));
        idx = 0;
        while (iter.hasNext()) {
            KV<String, String> kv = iter.next();
            assertEquals(getKey(idx), kv.key());
            assertEquals(getValue(idx), kv.value());
            ++idx;
            if (10 == idx) {
                idx = 21;
            }
        }
        assertEquals(100, idx);
        iter.close();
    }

    @Test
    public void testTxnCompareSuccess() throws Exception {
        store.init(spec);
        // write 10 kvs at revision 99L
        writeKVs(20, 99L);
        TxnOp<String, String> txnOp = store.getOpFactory().buildTxnOp()
            .revision(100L)
            .addCompareOps(
                store.getOpFactory().compareCreateRevision(
                    CompareResult.EQUAL,
                    getKey(10),
                    99L))
            .addSuccessOps(
                store.getOpFactory().buildPutOp()
                    .key(getKey(11))
                    .value("test-value")
                    .prevKV(true)
                    .build())
            .addFailureOps(
                store.getOpFactory().buildDeleteOp()
                    .key(getKey(11))
                    .build())
            .build();
        TxnResult<String, String> result = store.txn(txnOp);
        assertEquals(Code.OK, result.code());
        assertEquals(100L, result.revision());
        assertEquals(1, result.results().size());
        assertTrue(result.isSuccess());
        Result<String, String> subResult = result.results().get(0);
        assertEquals(OpType.PUT, subResult.type());
        PutResult<String, String> putResult = (PutResult<String, String>) subResult;
        KVRecord<String, String> prevResult = putResult.prevKV();
        assertEquals(getKey(11), prevResult.key());
        assertEquals(getValue(11), prevResult.value());
        assertEquals(99L, prevResult.createRevision());
        assertEquals(99L, prevResult.modifiedRevision());
        assertEquals(0L, prevResult.version());
        result.recycle();

        assertEquals("test-value", store.get(getKey(11)));
    }


    @Test
    public void testTxnCompareFailure() throws Exception {
        store.init(spec);
        // write 10 kvs at revision 99L
        writeKVs(20, 99L);
        TxnOp<String, String> txnOp = store.getOpFactory().buildTxnOp()
            .revision(100L)
            .addCompareOps(
                store.getOpFactory().compareCreateRevision(
                    CompareResult.NOT_EQUAL,
                    getKey(10),
                    99L))
            .addSuccessOps(
                store.getOpFactory().buildPutOp()
                    .key(getKey(11))
                    .value("test-value")
                    .prevKV(true)
                    .build())
            .addFailureOps(
                store.getOpFactory().buildDeleteOp()
                    .key(getKey(11))
                    .prevKV(true)
                    .build())
            .build();
        TxnResult<String, String> result = store.txn(txnOp);
        assertEquals(Code.OK, result.code());
        assertEquals(100L, result.revision());
        assertEquals(1, result.results().size());
        assertFalse(result.isSuccess());
        Result<String, String> subResult = result.results().get(0);
        assertEquals(OpType.DELETE, subResult.type());
        DeleteResult<String, String> deleteResult = (DeleteResult<String, String>) subResult;
        List<KVRecord<String, String>> prevResults = deleteResult.prevKvs();
        assertEquals(1, prevResults.size());
        KVRecord<String, String> prevResult = prevResults.get(0);
        assertEquals(getKey(11), prevResult.key());
        assertEquals(getValue(11), prevResult.value());
        assertEquals(99L, prevResult.createRevision());
        assertEquals(99L, prevResult.modifiedRevision());
        assertEquals(0L, prevResult.version());
        result.recycle();

        assertEquals(null, store.get(getKey(11)));
    }

}