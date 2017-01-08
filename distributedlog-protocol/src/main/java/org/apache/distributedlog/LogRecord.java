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
package org.apache.distributedlog;

import com.google.common.annotations.VisibleForTesting;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Log record is the basic element in a log.
 *
 * <p>A log is a sequence of log records. Each log record is a sequence of bytes.
 * Log records are written sequentially into a stream, and will be assigned with
 * an unique system generated sequence number {@link DLSN} (distributedlog sequence
 * number). Besides {@link DLSN}, application can assign its own sequence number
 * while constructing log records. The application defined sequence number is called
 * <code>TransactionID</code> (<i>txid</i>). Either {@link DLSN} or <code>TransactionId</code>
 * could be used to position readers to start from specific log records.
 *
 * <h3>User Record</h3>
 *
 * <p>User records are the records written by applications and read by applications. They
 * are constructed via {@link #LogRecord(long, byte[])} by applications and appended to
 * logs by writers. And they would be deserialized from bytes by the readers and return
 * to applications.
 *
 * <h3>Control Record</h3>
 *
 * <p>Control records are special records that written by distributedlog. They are invisible
 * to applications. They could be treated as <i>commit requests</i> as what people could find
 * in distributed consensus algorithms, since they are usually written by distributedlog to
 * commit application written records. <i>Commit</i> means making application written records
 * visible to readers to achieve consistent views among them.
 *
 * <p>They are named as 'Control Records' for controlling visibility of application written records.
 *
 * <p>The transaction id of 'Control Records' are assigned by distributedlog by inheriting from last
 * written user records. So we could indicate what user records that a control record is committing
 * by looking at its transaction id.
 *
 * <h4>EndOfStream Record</h4>
 *
 * <p><code>EoS</code>(EndOfStream) is a special control record that would be written by a writer
 * to seal a log. After a <i>EoS</i> record is written to a log, no writers could append any record
 * after that and readers will get {@link org.apache.distributedlog.exceptions.EndOfStreamException}
 * when they reach EoS.
 *
 * <p>TransactionID of EoS is <code>Long.MAX_VALUE</code>.
 *
 * <h3>Serialization & Deserialization</h3>
 *
 * <p>Data type in brackets. Interpretation should be on the basis of data types and not individual
 * bytes to honor Endianness.
 *
 * <pre>
 * LogRecord structure:
 * -------------------
 * Bytes 0 - 7                      : Metadata (Long)
 * Bytes 8 - 15                     : TxId (Long)
 * Bytes 16 - 19                    : Payload length (Integer)
 * Bytes 20 - 20+payload.length-1   : Payload (Byte[])
 *
 * Metadata: 8 Bytes (Long)
 * --------
 *
 * 0x 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
 *            |_____________| |_____|
 *                   |           |
 *               position      flags
 *
 * Flags: 2 Bytes (least significant)
 * -----
 * Bit  0      : If set, control record, else record with payload.
 * Bit  1      : If set, end of stream.
 * Bits 2 - 15 : Unused
 * </pre>
 *
 * <h3>Sequence Numbers</h3>
 *
 * <p>A record is associated with three types of sequence numbers. They are generated
 * and used for different purposes. Check {@link LogRecordWithDLSN} for more details.
 *
 * @see LogRecordWithDLSN
 */
public class LogRecord {

    private static final Logger LOG = LoggerFactory.getLogger(LogRecord.class);

    // Allow 4K overhead for metadata within the max transmission size
    public static final int MAX_LOGRECORD_SIZE = 1024 * 1024 - 8 * 1024; //1MB - 8KB
    // Allow 4K overhead for transmission overhead
    public static final int MAX_LOGRECORDSET_SIZE = 1024 * 1024 - 4 * 1024; //1MB - 4KB

    private static final int INPUTSTREAM_MARK_LIMIT = 16;

    static final long LOGRECORD_METADATA_FLAGS_MASK = 0xffffL;
    static final long LOGRECORD_METADATA_FLAGS_UMASK = 0xffffffffffff0000L;
    static final long LOGRECORD_METADATA_POSITION_MASK = 0x0000ffffffff0000L;
    static final long LOGRECORD_METADATA_POSITION_UMASK = 0xffff00000000ffffL;
    static final int LOGRECORD_METADATA_POSITION_SHIFT = 16;
    static final long LOGRECORD_METADATA_UNUSED_MASK = 0xffff000000000000L;

    // TODO: Replace with EnumSet
    static final long LOGRECORD_FLAGS_CONTROL_MESSAGE = 0x1;
    static final long LOGRECORD_FLAGS_END_OF_STREAM = 0x2;
    static final long LOGRECORD_FLAGS_RECORD_SET = 0x4;

    private long metadata;
    private long txid;
    private final ByteBuffer payload;

    /**
     * Construct a log record with <i>TransactionId</i> and payload.
     *
     * <p>Usually writer would construct the log record for writing.
     *
     * @param txid
     *          application defined transaction id.
     * @param payload
     *          record data
     */
    public LogRecord(long txid, byte[] payload) {
        this(txid, ByteBuffer.wrap(payload));
    }

    public LogRecord(long txid, ByteBuffer buffer) {
        this(txid, buffer, 0);
    }

    protected LogRecord(long txid, ByteBuffer buffer, long metadata) {
        this.txid = txid;
        this.payload = buffer;
        this.metadata = metadata;
    }

    //
    // Accessors
    //

    /**
     * Return application defined transaction id.
     *
     * @return transacton id.
     */
    public long getTransactionId() {
        return txid;
    }

    /**
     * Set application defined transaction id.
     *
     * @param txid application defined transaction id.
     */
    protected void setTransactionId(long txid) {
        this.txid = txid;
    }

    /**
     * Return the payload of this log record.
     *
     * <p>Deprecated. Please use {@link #getPayloadBuffer()}.
     *
     * @return payload of this log record.
     */
    public byte[] getPayload() {
        if (payload.hasArray() && payload.arrayOffset() == 0) {
            byte[] data = payload.array();
            if (data.length == payload.limit()) {
                return data;
            }
        }
        byte[] payloadArr = new byte[payload.remaining()];
        payload.duplicate().get(payloadArr);
        return payloadArr;
    }

    public ByteBuffer getPayloadBuffer() {
        return payload.slice();
    }

    /**
     * Return the payload as an {@link InputStream}.
     *
     * @return payload as input stream
     */
    public InputStream getPayLoadInputStream() {
        return new ByteBufInputStream(Unpooled.wrappedBuffer(payload));
    }

    //
    // Metadata & Flags
    //

    protected void setMetadata(long metadata) {
        this.metadata = metadata;
    }

    protected long getMetadata() {
        return this.metadata;
    }

    /**
     * Set the position in the log segment.
     *
     * @see #getPositionWithinLogSegment()
     * @param positionWithinLogSegment position in the log segment.
     */
    void setPositionWithinLogSegment(int positionWithinLogSegment) {
        metadata = setPositionWithinLogSegment(metadata, positionWithinLogSegment);
    }

    static long setPositionWithinLogSegment(long metadata,
                                            int positionWithinLogSegment) {
        assert(positionWithinLogSegment >= 0);
        return (metadata & LOGRECORD_METADATA_POSITION_UMASK)
            | (((long) positionWithinLogSegment) << LOGRECORD_METADATA_POSITION_SHIFT);
    }

    /**
     * The position in the log segment means how many records (inclusive) added to the log segment so far.
     *
     * @return position of the record in the log segment.
     */
    public int getPositionWithinLogSegment() {
        long ret = (metadata & LOGRECORD_METADATA_POSITION_MASK) >> LOGRECORD_METADATA_POSITION_SHIFT;
        if (ret < 0 || ret > Integer.MAX_VALUE) {
            throw new IllegalArgumentException
                (ret + " position should never exceed max integer value");
        }
        return (int) ret;
    }

    /**
     * Get the last position of this record in the log segment.
     *
     * <p>If the record isn't record set, it would be same as {@link #getPositionWithinLogSegment()},
     * otherwise, it would be {@link #getPositionWithinLogSegment()} + numRecords - 1. If the record set
     * version is unknown, it would be same as {@link #getPositionWithinLogSegment()}.
     *
     * @return last position of this record in the log segment.
     */
    int getLastPositionWithinLogSegment() {
        if (isRecordSet()) {
            try {
                return getPositionWithinLogSegment() + LogRecordSet.numRecords(this) - 1;
            } catch (IOException e) {
                // if it is unrecognized record set, we will return the position of this record set.
                return getPositionWithinLogSegment();
            }
        } else {
            return getPositionWithinLogSegment();
        }
    }

    /**
     * Set the record to represent a set of records.
     *
     * <p>The bytes in this record is the serialized format of {@link LogRecordSet}.
     */
    public void setRecordSet() {
        metadata = metadata | LOGRECORD_FLAGS_RECORD_SET;
    }

    /**
     * Check if the record represents a set of records.
     *
     * @return true if the record represents a set of records, otherwise false.
     * @see #setRecordSet()
     */
    public boolean isRecordSet() {
        return isRecordSet(metadata);
    }

    public static boolean isRecordSet(long metadata) {
        return ((metadata & LOGRECORD_FLAGS_RECORD_SET) != 0);
    }

    @VisibleForTesting
    public void setControl() {
        metadata = metadata | LOGRECORD_FLAGS_CONTROL_MESSAGE;
    }

    /**
     * Check if the record is a control record.
     *
     * @return true if the record is a control record, otherwise false.
     */
    public boolean isControl() {
        return isControl(metadata);
    }

    /**
     * Check flags to see if it indicates a control record.
     *
     * @param flags record flags
     * @return true if the record is a control record, otherwise false.
     */
    public static boolean isControl(long flags) {
        return ((flags & LOGRECORD_FLAGS_CONTROL_MESSAGE) != 0);
    }

    /**
     * Set the record as <code>EoS</code> mark.
     *
     * @see #isEndOfStream()
     */
    void setEndOfStream() {
        metadata = metadata | LOGRECORD_FLAGS_END_OF_STREAM;
    }

    /**
     * Check if the record is a <code>EoS</code> mark.
     *
     * <p><code>EoS</code> mark is a special record that writer would
     * add to seal a log. after <code>Eos</code> mark is written,
     * writers can't write any more records and readers will get
     * {@link org.apache.distributedlog.exceptions.EndOfStreamException}
     * when they reach <code>EoS</code>.
     *
     * @return true
     */
    boolean isEndOfStream() {
        return ((metadata & LOGRECORD_FLAGS_END_OF_STREAM) != 0);
    }

    //
    // Serialization & Deserialization
    //

    private void writePayload(ByteBuf out) throws IOException {
        ByteBuffer bufferToWrite = payload.slice();
        out.writeInt(bufferToWrite.remaining());
        out.writeBytes(bufferToWrite);
    }

    private void writeToStream(ByteBuf out) throws IOException {
        out.writeLong(metadata);
        out.writeLong(txid);
        writePayload(out);
    }

    /**
     * The size of the serialized log record.
     *
     * <p>This is used to estimate how much will be be appended to the in-memory buffer.
     *
     * @return serialized size
     */
    int getPersistentSize() {
        // Flags + TxId + Payload-length + payload
        return 2 * (Long.SIZE / 8) + Integer.SIZE / 8 + payload.remaining();
    }

    /**
     * Writer class to write log records into an output {@code stream}.
     */
    public static class Writer {
        private final ByteBuf buf;

        public Writer(ByteBuf out) {
            this.buf = out;
        }

        /**
         * Write an operation to the output stream.
         *
         * @param record The operation to write
         * @throws IOException if an error occurs during writing.
         */
        public void writeOp(LogRecord record) throws IOException {
            record.writeToStream(buf);
        }

        public int getPendingBytes() {
            return buf.readableBytes();
        }
    }

    /**
     * Reader class to read log records from an input {@code stream}.
      */
    public static class Reader {
        private final RecordStream recordStream;
        private final ByteBuf in;
        private final long startSequenceId;
        private final boolean deserializeRecordSet;
        private LogRecordSet.Reader recordSetReader = null;
        private LogRecordWithDLSN lastRecordSkipTo = null;

        public Reader(RecordStream recordStream,
                      ByteBuf in,
                      long startSequenceId,
                      boolean deserializeRecordSet) {
            this.recordStream = recordStream;
            this.in = in;
            this.startSequenceId = startSequenceId;
            this.deserializeRecordSet = deserializeRecordSet;
        }

        // The caller should pass in a heap buffer
        protected static ByteBuffer readPayload(ByteBuf in,
                                                boolean allocateBuffer)
                throws IOException {
            int length = in.readInt();
            if (length < 0) {
                throw new EOFException("Log Record is corrupt: Negative length " + length);
            }
            if (length > in.readableBytes()) {
                throw new IOException("Log record is corrupt : No enough data - length = "
                        + length + " but only " + in.readableBytes() + " is available");
            }
            if (!allocateBuffer) {
                try {
                    ByteBuffer buffer = in.nioBuffer(in.readerIndex(), length);
                    in.skipBytes(length);
                    return buffer;
                } catch (UnsupportedOperationException noe) {
                    LOG.debug("Can't create nio buffer. Then allocate a new nio buffer.", noe);
                }
            }
            ByteBuffer payload = ByteBuffer.allocate(length);
            in.readBytes(payload);
            payload.flip();
            return payload;
        }

        /**
         * Read an log record from the input stream.
         *
         * <p/> Note that the objects returned from this method may be re-used by future
         * calls to the same method.
         *
         * @return the operation read from the stream, or null at the end of the file
         * @throws IOException on error.
         */
        public LogRecordWithDLSN readOp() throws IOException {
            return readOp(true);
        }

        LogRecordWithDLSN readOp(boolean allocateBuffer) throws IOException {
            LogRecordWithDLSN nextRecordInStream;
            while (true) {
                if (lastRecordSkipTo != null) {
                    nextRecordInStream = lastRecordSkipTo;
                    recordStream.advance(1);
                    lastRecordSkipTo = null;
                    return nextRecordInStream;
                }
                if (recordSetReader != null) {
                    nextRecordInStream = recordSetReader.nextRecord();
                    if (null != nextRecordInStream) {
                        recordStream.advance(1);
                        return nextRecordInStream;
                    } else {
                        recordSetReader = null;
                    }
                }

                if (in.readableBytes() <= 0) {
                    break;
                }

                try {
                    long metadata = in.readLong();
                    // Reading the first 8 bytes positions the record stream on the correct log record
                    // By this time all components of the DLSN are valid so this is where we shoud
                    // retrieve the currentDLSN and advance to the next
                    // Given that there are 20 bytes following the read position of the previous call
                    // to readLong, we should not have moved ahead in the stream.
                    long txId = in.readLong();
                    ByteBuffer payload = readPayload(in, allocateBuffer);
                    nextRecordInStream = new LogRecordWithDLSN(
                            recordStream.getCurrentPosition(),
                            startSequenceId,
                            txId,
                            payload,
                            metadata);
                    if (LOG.isTraceEnabled()) {
                        if (nextRecordInStream.isControl()) {
                            LOG.trace("Reading {} Control DLSN {}",
                                recordStream.getName(), nextRecordInStream.getDlsn());
                        } else {
                            LOG.trace("Reading {} Valid DLSN {}",
                                recordStream.getName(), nextRecordInStream.getDlsn());
                        }
                    }

                    int numRecords = 1;
                    if (!deserializeRecordSet && nextRecordInStream.isRecordSet()) {
                        numRecords = LogRecordSet.numRecords(nextRecordInStream);
                    }

                    if (deserializeRecordSet && nextRecordInStream.isRecordSet()) {
                        recordSetReader = LogRecordSet.of(nextRecordInStream, allocateBuffer);
                    } else {
                        recordStream.advance(numRecords);
                        return nextRecordInStream;
                    }
                } catch (EOFException eof) {
                    // Expected
                    break;
                }
            }
            return null;
        }

        public boolean skipTo(long txId, boolean skipControl) throws IOException {
            return skipTo(txId, null, skipControl);
        }

        public boolean skipTo(DLSN dlsn) throws IOException {
            return skipTo(null, dlsn, false);
        }

        private boolean skipTo(Long txId, DLSN dlsn, boolean skipControl) throws IOException {
            LOG.debug("SkipTo");
            boolean found = false;
            while (true) {
                try {
                    long flags;
                    long currTxId;

                    // if there is not record set, read next record
                    if (null == recordSetReader) {
                        if (in.readableBytes() <= 0) {
                            break;
                        }
                        in.markReaderIndex();
                        flags = in.readLong();
                        currTxId = in.readLong();
                    } else {
                        // check record set until reach end of record set
                        lastRecordSkipTo = recordSetReader.nextRecord();
                        if (null == lastRecordSkipTo) {
                            // reach end of record set
                            recordSetReader = null;
                            continue;
                        }
                        flags = lastRecordSkipTo.getMetadata();
                        currTxId = lastRecordSkipTo.getTransactionId();
                    }

                    if ((null != dlsn) && (recordStream.getCurrentPosition().compareTo(dlsn) >= 0)) {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Found position {} beyond {}", recordStream.getCurrentPosition(), dlsn);
                        }
                        if (null == lastRecordSkipTo) {
                            in.resetReaderIndex();
                        }
                        found = true;
                        break;
                    }
                    if ((null != txId) && (currTxId >= txId)) {
                        if (!skipControl || !isControl(flags)) {
                            if (LOG.isTraceEnabled()) {
                                LOG.trace("Found position {} beyond {}", currTxId, txId);
                            }
                            if (null == lastRecordSkipTo) {
                                in.resetReaderIndex();
                            }
                            found = true;
                            break;
                        }
                    }

                    if (null != lastRecordSkipTo) {
                        recordStream.advance(1);
                        continue;
                    }

                    // get the num of records to skip
                    if (isRecordSet(flags)) {
                        // read record set
                        ByteBuffer payload = readPayload(in, true);
                        LogRecordWithDLSN record = new LogRecordWithDLSN(
                                recordStream.getCurrentPosition(),
                                startSequenceId,
                                currTxId,
                                payload,
                                flags);
                        recordSetReader = LogRecordSet.of(record, true);
                    } else {
                        int length = in.readInt();
                        if (length < 0) {
                            // We should never really see this as we only write complete entries to
                            // BK and BK client has logic to detect torn writes (through checksum)
                            LOG.info("Encountered Record with negative length at TxId: {}", currTxId);
                            break;
                        }
                        // skip single record
                        in.skipBytes(length);
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Skipped Record with TxId {} DLSN {}",
                                currTxId, recordStream.getCurrentPosition());
                        }
                        recordStream.advance(1);
                    }
                } catch (EOFException eof) {
                    LOG.debug("Skip encountered end of file Exception", eof);
                    break;
                }
            }
            return found;
        }
    }
}
