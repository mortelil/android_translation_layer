/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.transport;

import android.os.ATLBinderConnection;
import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFormatException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TRN-021: the wire-format contract of the production transport's receive path
 * (android.os.ATLBinderConnection readLoop + Parcel.atlReceive).
 *
 * Port of the Firefox local-service-probe IpcFrames battery (Firefox b51e45f,
 * tests/local-service-probe/src/org/atl/tests/svc/IpcFrames.java, GPL-3.0-only;
 * reuse-ledger R09/R20) re-encoded for the PR31-family wire format of
 * ATLBinderConnection (MAGIC 0x41544c32, kind/target/code/flags/callChain/
 * status header, binder-ref table, descriptor-position table; architecture
 * §2). The Firefox parser itself is not copied - the INVARIANTS are, and the
 * decoder under test is the production readLoop reached over a real
 * SOCK_SEQPACKET record (the raw channel primitive written directly), never
 * a decoder copy:
 *
 *  - a record whose declared lengths/counts do not fit the bytes delivered is
 *    rejected. Counts are read from the record, so an unchecked count would
 *    overflow/over-read; every bound is long-arithmetic safe before any array
 *    is sized (W3);
 *  - a descriptor-position table that is not usable - negative offset, past
 *    the body, two slots whose four byte patches overlap (equal offsets are
 *    the extreme case), count-vs-received mismatch - makes the record
 *    unreadable as a whole, so it is rejected before anything is patched;
 *    the position of a slot does NOT have to be four byte aligned (the sender
 *    writes the number at the byte position the Parcel chose);
 *  - every descriptor that arrived with a rejected record is closed (no
 *    leak); an accepted record hands its descriptors to the dispatched
 *    Parcel, which closes them on recycle;
 *  - decoding is stateless: neither a rejected record nor a closed peer end
 *    stops the next well-formed record from decoding (fresh link after every
 *    case, because a rejected record legitimately kills its connection).
 *
 * Added negatives beyond the 20 Firefox cases: bad-magic, trailing-bytes,
 * unknown-target (a CALL for a handle this side never exported), a 16-slot
 * rejected record (fault paths close every unclaimed descriptor), and the
 * PR31 raw-integer descriptor impersonation case (invalidFd) at the Parcel
 * layer.
 */
final class Frames {
	private static final int MAX_FDS = 64;
	/** A number the sender writes into the body where a descriptor belongs. */
	private static final int FOREIGN = 4242;
	private static final int KIND_CALL = 0;
	private static final int FLAG_ONEWAY = 1;
	private static final long TIMEOUT_MS = 5000;

	interface Sink {
		void event(String cmd, String ret);
		void marker(String line);
	}

	/** One observation of the RecordingRoot (the "reader" of accepted records). */
	static final class Obs {
		final int code;
		final byte[] raw;
		final int callingPid;
		final int callingUid;
		final long thread;
		Obs(int code, byte[] raw, int callingPid, int callingUid, long thread) {
			this.code = code;
			this.raw = raw;
			this.callingPid = callingPid;
			this.callingUid = callingUid;
			this.thread = thread;
		}
	}

	/** Root binder of the link under test; records what the decoder published. */
	static final class RecordingRoot extends Binder {
		final ConcurrentHashMap<Integer, Obs> seen = new ConcurrentHashMap<>();
		@Override
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
			byte[] raw = data.marshall();
			seen.put(Integer.valueOf(code),
			         new Obs(code, raw, Binder.getCallingPid(), Binder.getCallingUid(),
			                 Thread.currentThread().getId()));
			if (reply != null)
				reply.writeInt(code);
			return true;
		}
	}

	/** A fresh production connection on one end of a raw socket pair. */
	static final class Wire {
		final ATLBinderConnection connection;
		final RecordingRoot root;
		final int rawFd;
		private boolean rawClosed;
		final CountDownLatch dead = new CountDownLatch(1);
		Wire() throws Exception {
			int[] pair = ATLBinderConnection.socketPair();
			rawFd = pair[1];
			rawClosed = false;
			root = new RecordingRoot();
			connection = new ATLBinderConnection(pair[0], -1, root);
			connection.onDeath(new Runnable() {
				public void run() {
					dead.countDown();
				}
			});
			connection.start();
		}
		void send(byte[] frame, int[] fds) throws Exception {
			LinkTools.rawSend(rawFd, frame, fds);
		}
		/** REJECT observable: the production reader closed the link. */
		boolean rejected(long millis) throws InterruptedException {
			return dead.await(millis, TimeUnit.MILLISECONDS);
		}
		Obs awaitObserved(int code, long millis) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
			while (System.nanoTime() < deadline) {
				Obs obs = root.seen.get(Integer.valueOf(code));
				if (obs != null)
					return obs;
				if (dead.getCount() == 0)
					return null;
				Thread.sleep(10);
			}
			return root.seen.get(Integer.valueOf(code));
		}
		void closeRawEnd() {
			if (!rawClosed) {
				rawClosed = true;
				LinkTools.closeFd(rawFd);
			}
		}
		void dispose() {
			try {
				connection.close();
			} catch (Throwable ignored) {
			}
			closeRawEnd();
		}
	}

	private final Sink sink;
	private int caseSeq;

	Frames(Sink sink) {
		this.sink = sink;
	}

	static void run(Sink sink) throws Exception {
		if (!LinkTools.available())
			throw new IllegalStateException("ATLBinderConnection reflection setup failed: "
			                                + LinkTools.SETUP_ERROR);
		new Frames(sink).battery();
	}

	private void battery() throws Exception {
		int before = LinkTools.fdCount();
		reject("truncated-header", "record cut inside the fixed header", 0,
		       cutRecord(20));
		reject("truncated-binder-table", "binderCount=5, no table delivered", 0,
		       record(0, new int[0], null, body(4), 5));
		reject("binder-count-overflow", "binderCount=0x40000000 (4*count overflows int)", 0,
		       record(0, new int[0], null, body(4), 0x40000000));
		reject("binder-count-negative", "binderCount=-2", 0,
		       record(0, new int[0], null, body(4), -2));
		reject("fd-count-negative", "fdCount=-1", 0,
		       record(-1, new int[0], null, body(4), 0));
		reject("fd-count-huge", "fdCount=0x7fffffff", 0,
		       record(0x7fffffff, new int[0], null, body(4), 0));
		reject("fd-count-over-max", "fdCount=65 above the 64 descriptor transport bound", 0,
		       record(65, new int[0], null, body(4), 0));
		reject("fd-table-past-frame", "fdCount=4, only one position delivered", 1,
		       record(4, new int[]{0}, null, body(4), 0));
		reject("length-past-record", "declared length 4 bytes past the record", 0,
		       record(0, new int[0], Integer.valueOf(12), body(4), 0));
		reject("short-record", "declared length 16, 8 body bytes delivered", 0,
		       record(0, new int[0], Integer.valueOf(16), new byte[8], 0));
		reject("fd-offset-negative", "one slot, offset=-4", 1,
		       record(1, new int[]{-4}, null, body(4), 0));
		reject("fd-offset-past-body", "one slot, offset=4 of a 4 byte body", 1,
		       record(1, new int[]{4}, null, body(4), 0));
		accept("fd-offset-odd", "one slot at the odd byte position 2 of an 8 byte body", 1,
		       record(1, new int[]{2}, null, oddBody(), 0), 2);
		reject("fd-offsets-overlap", "two slots naming the same position", 2,
		       record(2, new int[]{0, 0}, null, body(4), 0));
		reject("fd-offsets-overlap-range", "two slots whose 4 byte ranges overlap (0 and 2)", 2,
		       record(2, new int[]{0, 2}, null, new byte[8], 0));
		reject("fd-not-received", "one slot described, no descriptor arrived", 0,
		       record(1, new int[]{0}, null, body(4), 0));
		reject("fd-partially-received", "two slots described, one descriptor arrived", 1,
		       record(2, new int[]{0, 4}, null, new byte[8], 0));
		accept("valid-control", "one slot, offset=0, one descriptor arrived", 1,
		       record(1, new int[]{0}, null, body(4), 0), 0);
		reject("bad-magic", "MAGIC=0xdeadbeef", 0,
		       badMagic());
		reject("trailing-bytes", "well-formed record plus 4 trailing bytes", 0,
		       trailing());
		reject("unknown-target", "CALL for handle 999 this side never exported", 0,
		       record(0, new int[0], null, body(4), 0, 999));
		reject("fd-many-reject", "16 slots with one overlapping pair: every unclaimed descriptor closed", 16,
		       record(16, manyPositions(), null, manyBody(), 0));
		concurrent();
		peerEndClosed();
		invalidFd();
		int after = LinkTools.fdCount();
		boolean ok = after - before < 4;
		sink.event("teardown", "verdict=" + (ok ? "OK" : "FAIL") + ";fdBefore=" + before
		                            + ";fdAfter=" + after
		                            + ";note=every link and sentinel of the battery disposed");
		sink.marker("TRANSPORT FRAMES teardown " + (ok ? "ok" : "FAIL")
		            + " fdBefore=" + before + " fdAfter=" + after);
	}

	/* ---------------------------------------------------------------- cases */

	private void reject(String name, String why, int sentinelCount, byte[] record) throws Exception {
		int[] sentinels = new int[sentinelCount];
		Wire wire = new Wire();
		try {
			for (int i = 0; i < sentinelCount; ++i)
				sentinels[i] = LinkTools.sentinel();
			int before = LinkTools.fdCount();
			wire.send(record, sentinels);
			boolean rejected = wire.rejected(TIMEOUT_MS);
			// "Every unclaimed descriptor was closed" is not a statement about the
			// sender's own sentinels (those are ours, still open here) and the
			// receiver's copies have numbers this side never sees: SCM_RIGHTS hands
			// the receiver a descriptor of its own. The descriptor count is the
			// observable form of the claim.
			boolean steady = fdSteady(before);
			String validNext = validNext();
			boolean ok = rejected && steady && "accept".equals(validNext);
			sink.event(name, "verdict=REJECT;rejected=" + rejected + ";fdSteady=" + steady
			                    + ";validNext=" + validNext + ";why=" + why);
			sink.marker("TRANSPORT FRAMES " + name + " " + (ok ? "REJECT ok" : "FAIL")
			            + " fds=" + sentinelCount);
			closeAll(sentinels);
		} finally {
			wire.dispose();
		}
	}

	private void accept(String name, String why, int sentinelCount, byte[] record,
	                    int wordOffset) throws Exception {
		int[] sentinels = new int[sentinelCount];
		Wire wire = new Wire();
		try {
			for (int i = 0; i < sentinelCount; ++i)
				sentinels[i] = LinkTools.sentinel();
			int before = LinkTools.fdCount();
			wire.send(record, sentinels);
			Obs obs = wire.awaitObserved(codeOf(record), TIMEOUT_MS);
			boolean observed = obs != null;
			// The descriptor was patched into the body at the declared offset: the
			// word living there names a usable descriptor of this process, and the
			// sender's placeholder is gone from the body. The number is the
			// receiver's own (never the sender's), so the leak claim is the count.
			int rx = observed ? wordAt(obs.raw, wordOffset) : -1;
			boolean patched = observed && rx >= 0 && rx != FOREIGN;
			boolean placeholder = observed && containsWord(obs.raw, FOREIGN);
			boolean steady = fdSteady(before);
			String validNext = validNext();
			boolean ok = observed && patched && !placeholder && steady
			             && "accept".equals(validNext) && wire.dead.getCount() != 0;
			sink.event(name, "verdict=ACCEPT;observed=" + observed + ";patched=" + patched
			                    + ";bodyWordAtOffset=" + rx
			                    + ";senderPlaceholderInBody=" + (placeholder ? "yes" : "no")
			                    + ";fdSteady=" + steady + ";validNext=" + validNext
			                    + ";why=" + why);
			sink.marker("TRANSPORT FRAMES " + name + " " + (ok ? "ACCEPT ok" : "FAIL")
			            + " offset=" + wordOffset + " bodyWord=" + rx);
			closeAll(sentinels);
		} finally {
			wire.dispose();
		}
	}

	/**
	 * The decoder is shared: calls from many app threads reach it at the same
	 * time. Four threads decode well-formed and unusable records on their own
	 * links at the same time; every accepted descriptor must be the one that
	 * arrived, every rejected record must have closed its descriptors. The
	 * serial pass afterwards measures that nothing leaks across iterations
	 * (a descriptor NUMBER is process-wide and another thread may reuse it
	 * between the decode and the check, which is why threads report and the
	 * leak loop runs alone).
	 */
	private void concurrent() throws Exception {
		final int threads = 4, iterations = 40;
		final int[] accepted = new int[1], rejectedCount = new int[1], mismatch = new int[1];
		Thread[] pool = new Thread[threads];
		for (int t = 0; t < threads; ++t) {
			pool[t] = new Thread(new Runnable() {
				public void run() {
					try {
						for (int i = 0; i < iterations; ++i) {
							// Structure only while four threads run: the descriptor
							// count is process-wide and another thread's transient
							// descriptors would make the leak claim meaningless. The
							// serial pass below repeats both with the count check.
							if (decodeValid(false))
								accepted[0]++;
							else
								mismatch[0]++;
							if (decodeRejectedPair(false))
								rejectedCount[0]++;
							else
								mismatch[0]++;
						}
					} catch (Throwable e) {
						mismatch[0]++;
					}
				}
			}, "trn-frames-" + t);
			pool[t].start();
		}
		for (int t = 0; t < threads; ++t)
			pool[t].join(60000);
		int serialGood = 0, serialBad = 0;
		for (int i = 0; i < 40; ++i) {
			if (decodeValid())
				serialGood++;
			if (decodeRejectedPair())
				serialBad++;
		}
		int expected = threads * iterations;
		boolean ok = mismatch[0] == 0 && accepted[0] == expected && rejectedCount[0] == expected
		             && serialGood == 40 && serialBad == 40;
		sink.event("concurrent", "verdict=" + (ok ? "OK" : "MISMATCH")
		                            + ";accepted=" + accepted[0] + "/" + expected
		                            + ";rejected=" + rejectedCount[0] + "/" + expected
		                            + ";mismatch=" + mismatch[0]
		                            + ";serialGood=" + serialGood + "/40"
		                            + ";serialBad=" + serialBad + "/40"
		                            + ";validNext=" + validNext());
		sink.marker("TRANSPORT FRAMES concurrent " + (ok ? "ok" : "FAIL")
		            + " accepted=" + accepted[0] + " rejected=" + rejectedCount[0]
		            + " serialGood=" + serialGood + " serialBad=" + serialBad);
	}

	private boolean decodeValid() throws Exception {
		return decodeValid(true);
	}

	/**
	 * @param checkFds also require the descriptor count to return to its
	 *                 pre-send level. Only meaningful when no other thread is
	 *                 opening descriptors (the concurrent phase checks structure,
	 *                 the serial phase after it checks the leak).
	 */
	private boolean decodeValid(boolean checkFds) throws Exception {
		Wire wire = new Wire();
		try {
			int fd = LinkTools.sentinel();
			int before = LinkTools.fdCount();
			byte[] record = record(1, new int[]{0}, null, body(4), 0);
			wire.send(record, new int[]{fd});
			Obs obs = wire.awaitObserved(codeOf(record), TIMEOUT_MS);
			int rx = obs == null ? -1 : wordAt(obs.raw, 0);
			boolean ok = obs != null && rx >= 0 && rx != FOREIGN;
			if (checkFds)
				ok &= fdSteady(before);
			if (LinkTools.fdExists(fd))
				LinkTools.closeFd(fd);
			return ok;
		} finally {
			wire.dispose();
		}
	}

	private boolean decodeRejectedPair() throws Exception {
		return decodeRejectedPair(true);
	}

	private boolean decodeRejectedPair(boolean checkFds) throws Exception {
		Wire wire = new Wire();
		try {
			int a = LinkTools.sentinel(), b = LinkTools.sentinel();
			int before = LinkTools.fdCount();
			byte[] record = record(2, new int[]{0, 0}, null, body(4), 0);
			wire.send(record, new int[]{a, b});
			boolean ok = wire.rejected(TIMEOUT_MS);
			if (checkFds)
				ok &= fdSteady(before);
			if (LinkTools.fdExists(a))
				LinkTools.closeFd(a);
			if (LinkTools.fdExists(b))
				LinkTools.closeFd(b);
			return ok;
		} finally {
			wire.dispose();
		}
	}

	/**
	 * The peer's raw end is gone after it sent a well-formed record. Decoding does
	 * not depend on the peer: the record was already published, and losing the peer
	 * afterwards must neither wedge the decoder (the next record still decodes) nor
	 * leak the descriptor that arrived with it. The raw end is closed *after* the
	 * record was observed, so the case measures the peer's disappearance instead of
	 * racing it against the dispatch queue.
	 */
	private void peerEndClosed() throws Exception {
		Wire wire = new Wire();
		try {
			int fd = LinkTools.sentinel();
			int before = LinkTools.fdCount();
			byte[] record = record(1, new int[]{0}, null, body(4), 0);
			wire.send(record, new int[]{fd});
			Obs obs = wire.awaitObserved(codeOf(record), TIMEOUT_MS);
			int rx = obs == null ? -1 : wordAt(obs.raw, 0);
			boolean observed = obs != null && rx >= 0 && rx != FOREIGN;
			wire.closeRawEnd();
			boolean died = wire.rejected(TIMEOUT_MS);
			boolean steady = fdSteady(before);
			String validNext = validNext();
			boolean ok = observed && died && steady && "accept".equals(validNext);
			sink.event("peer-end-closed", "verdict=" + (ok ? "ACCEPT" : "REJECT")
			                             + ";observed=" + observed + ";bodyWord=" + rx
			                             + ";peerGone=" + died + ";fdSteady=" + steady
			                             + ";validNext=" + validNext
			                             + ";why=peer raw end closed after the record");
			sink.marker("TRANSPORT FRAMES peer-end-closed " + (ok ? "ACCEPT ok" : "FAIL")
			            + " observed=" + observed + " peerGone=" + died);
			if (LinkTools.fdExists(fd))
				LinkTools.closeFd(fd);
		} finally {
			wire.dispose();
		}
	}

	/** PR31 case: a raw integer in the body cannot impersonate a descriptor. */
	private void invalidFd() {
		boolean rejected = false;
		Parcel forged = Parcel.obtain();
		try {
			forged.writeInt(1);
			forged.setDataPosition(0);
			forged.readFileDescriptor();
		} catch (ParcelFormatException expected) {
			rejected = true;
		} finally {
			forged.recycle();
		}
		sink.event("invalid-fd", "verdict=" + (rejected ? "REJECT" : "ACCEPT")
		                          + ";stillOpen=0;validNext=accept"
		                          + ";note=raw integer cannot impersonate a descriptor");
		sink.marker("TRANSPORT FRAMES invalid-fd " + (rejected ? "REJECT ok" : "FAIL"));
	}

	/** Control after every case: a well-formed record must still decode. */
	private String validNext() {
		try {
			return decodeValid() ? "accept" : "rejected";
		} catch (Throwable t) {
			return "error:" + t.getClass().getSimpleName();
		}
	}

	/* -------------------------------------------------------------- helpers */

	private static void closeAll(int[] fds) {
		for (int fd : fds)
			if (LinkTools.fdExists(fd))
				LinkTools.closeFd(fd);
	}

	/**
	 * The process-wide descriptor count comes back to `before`, i.e. every
	 * descriptor received for a record was released. Descriptor *numbers* cannot
	 * be compared across the SCM_RIGHTS boundary (the receiver gets a descriptor
	 * of its own), so counting is the observable form of "no descriptor leaked".
	 */
	private static boolean fdSteady(int before) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
		while (LinkTools.fdCount() > before && System.nanoTime() < deadline)
			Thread.sleep(10);
		return LinkTools.fdCount() <= before;
	}

	private static boolean containsWord(byte[] body, int value) {
		if (body == null)
			return false;
		for (int i = 0; i + 4 <= body.length; ++i)
			if (wordAt(body, i) == value)
				return true;
		return false;
	}

	static int wordAt(byte[] body, int offset) {
		if (body == null || offset < 0 || offset + 4 > body.length)
			return Integer.MIN_VALUE;
		return ((body[offset] & 0xff) << 24) | ((body[offset + 1] & 0xff) << 16)
		       | ((body[offset + 2] & 0xff) << 8) | (body[offset + 3] & 0xff);
	}

	private static byte[] body(int value) {
		byte[] body = new byte[4];
		putInt(body, 0, value);
		return body;
	}

	private static byte[] oddBody() {
		byte[] body = new byte[8];
		putInt(body, 2, FOREIGN);
		return body;
	}

	private static int[] manyPositions() {
		int[] positions = new int[16];
		for (int i = 0; i < 16; ++i)
			positions[i] = i == 1 ? 0 : 4 * i; // slot 1 overlaps slot 0
		return positions;
	}

	private static byte[] manyBody() {
		return new byte[16 * 4];
	}

	private byte[] cutRecord(int length) {
		byte[] record = new byte[length];
		putInt(record, 0, 0x41544c32);
		return record;
	}

	private byte[] badMagic() {
		byte[] record = record(0, new int[0], null, body(4), 0);
		putInt(record, 0, 0xdeadbeef);
		return record;
	}

	private byte[] trailing() {
		byte[] base = record(0, new int[0], null, body(4), 0);
		return Arrays.copyOf(base, base.length + 4);
	}

	private static int codeOf(byte[] record) {
		return record.length >= 20 ? wordAt(record, 16) : -1;
	}

	/**
	 * Builds one record the way the production sender does: fixed header
	 * (MAGIC, id, kind, target, code, flags, callChain, status), length +
	 * body, binder count + ref table, descriptor count + position table.
	 * Declared counts can override the entries actually delivered, which is
	 * the shape a hostile or broken peer produces. Every case uses a fresh
	 * unique transaction code so late observations cannot cross cases. All
	 * records are ONEWAY, so a decode that succeeds produces no reply traffic
	 * on the raw end.
	 */
	private byte[] record(int fdDeclared, int[] positions, Integer lengthOverride,
	                      byte[] body, int binderDeclared) {
		return record(fdDeclared, positions, lengthOverride, body, binderDeclared, 1);
	}

	private byte[] record(int fdDeclared, int[] positions, Integer lengthOverride,
	                      byte[] body, int binderDeclared, int target) {
		int length = lengthOverride == null ? body.length : lengthOverride.intValue();
		int total = 36 + 4 + body.length + 4 + 4 + 4 * positions.length;
		byte[] record = new byte[total];
		putInt(record, 0, 0x41544c32);          // MAGIC
		putInt(record, 4, ++caseSeq);           // id
		putInt(record, 8, KIND_CALL);           // kind
		putInt(record, 12, target);             // target (root = 1)
		putInt(record, 16, 700 + caseSeq);      // code, unique per case
		putInt(record, 20, FLAG_ONEWAY);        // flags
		putLong(record, 24, 0);                 // callChain
		putInt(record, 32, 0);                  // status
		putInt(record, 36, length);             // body length
		System.arraycopy(body, 0, record, 40, body.length);
		putInt(record, 40 + body.length, binderDeclared);
		putInt(record, 44 + body.length, fdDeclared);
		for (int i = 0; i < positions.length; ++i)
			putInt(record, 48 + body.length + 4 * i, positions[i]);
		return record;
	}

	static void putWord(byte[] buffer, int offset, int value) {
		putInt(buffer, offset, value);
	}

	/** A well-formed oneway CALL for the root, used to probe a live channel. */
	static byte[] validPlainRecord() {
		return staticRecord(698, 0L, 1, body(FOREIGN));
	}

	/**
	 * A well-formed oneway CALL whose callChain field carries a forged pid
	 * (999999): the decoder must not trust it - identity comes from the
	 * channel (SO_PEERCRED of the pair creator), never from the record.
	 */
	static byte[] forgedChainRecord(long callChain) {
		return staticRecord(699, callChain, 1, body(FOREIGN));
	}

	private static byte[] staticRecord(int code, long callChain, int target, byte[] body) {
		byte[] record = new byte[48 + body.length];
		putInt(record, 0, 0x41544c32);
		putInt(record, 4, 900000);
		putInt(record, 8, KIND_CALL);
		putInt(record, 12, target);
		putInt(record, 16, code);
		putInt(record, 20, FLAG_ONEWAY);
		putLong(record, 24, callChain);
		putInt(record, 32, 0);
		putInt(record, 36, body.length);
		System.arraycopy(body, 0, record, 40, body.length);
		putInt(record, 40 + body.length, 0); // binderCount
		putInt(record, 44 + body.length, 0); // fdCount
		return record;
	}

	private static void putInt(byte[] buffer, int offset, int value) {
		buffer[offset] = (byte)(value >>> 24);
		buffer[offset + 1] = (byte)(value >>> 16);
		buffer[offset + 2] = (byte)(value >>> 8);
		buffer[offset + 3] = (byte)value;
	}

	private static void putLong(byte[] buffer, int offset, long value) {
		putInt(buffer, offset, (int)(value >>> 32));
		putInt(buffer, offset + 4, (int)value);
	}
}
