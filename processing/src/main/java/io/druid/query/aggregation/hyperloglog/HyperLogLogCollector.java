/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.query.aggregation.hyperloglog;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.primitives.UnsignedBytes;
import com.metamx.common.IAE;
import com.metamx.common.ISE;
import com.metamx.common.logger.Logger;

import java.nio.ByteBuffer;

/**
 * Implements the HyperLogLog cardinality estimator described in:
 * <p/>
 * http://algo.inria.fr/flajolet/Publications/FlFuGaMe07.pdf
 * <p/>
 * Run this code to see a simple indication of expected errors based on different m values:
 * <p/>
 * for (int i = 1; i < 20; ++i) {
 * System.out.printf("i[%,d], val[%,d] => error[%f%%]%n", i, 2 << i, 104 / Math.sqrt(2 << i));
 * }
 * <p/>
 * This class is *not* multi-threaded.  It can be passed among threads, but it is written with the assumption that
 * only one thread is ever calling methods on it.
 * <p/>
 * If you have multiple threads calling methods on this concurrently, I hope you manage to get correct behavior
 */
public abstract class HyperLogLogCollector implements Comparable<HyperLogLogCollector>
{
  public static final int DENSE_THRESHOLD = 128;
  public static final int BITS_FOR_BUCKETS = 11;
  public static final int NUM_BUCKETS = 1 << BITS_FOR_BUCKETS;
  public static final int NUM_BYTES_FOR_BUCKETS = NUM_BUCKETS / 2;

  private static final double TWO_TO_THE_SIXTY_FOUR = Math.pow(2, 64);
  private static final double ALPHA = 0.7213 / (1 + 1.079 / NUM_BUCKETS);

  public static final double LOW_CORRECTION_THRESHOLD = (5 * NUM_BUCKETS) / 2.0d;
  public static final double HIGH_CORRECTION_THRESHOLD = TWO_TO_THE_SIXTY_FOUR / 30.0d;
  public static final double CORRECTION_PARAMETER = ALPHA * NUM_BUCKETS * NUM_BUCKETS;

  private static final Logger log = new Logger(HyperLogLogCollector.class);
  private static final int bucketMask = 0x7ff;
  private static final int minBytesRequired = 10;
  private static final int bitsPerBucket = 4;
  private static final int range = (int) Math.pow(2, bitsPerBucket) - 1;

  private final static double[][] minNumRegisterLookup = new double[64][256];

  static {
    for (int registerOffset = 0; registerOffset < 64; ++registerOffset) {
      for (int register = 0; register < 256; ++register) {
        final int upper = ((register & 0xf0) >> 4) + registerOffset;
        final int lower = (register & 0x0f) + registerOffset;
        minNumRegisterLookup[registerOffset][register] = 1.0d / Math.pow(2, upper) + 1.0d / Math.pow(2, lower);
      }
    }
  }

  // we have to keep track of the number of zeroes in each of the two halves of the byte register (0, 1, or 2)
  private final static int[] numZeroLookup = new int[256];

  static {
    for (int i = 0; i < numZeroLookup.length; ++i) {
      numZeroLookup[i] = (((i & 0xf0) == 0) ? 1 : 0) + (((i & 0x0f) == 0) ? 1 : 0);
    }
  }

  // Methods to build the latest HLLC
  public static HyperLogLogCollector makeLatestCollector()
  {
    return new HLLCV1();
  }

  public static HyperLogLogCollector makeCollector(ByteBuffer buffer)
  {
    int remaining = buffer.remaining();
    return (remaining % 3 == 0 || remaining == 1027) ? new HLLCV0(buffer) : new HLLCV1(buffer);
  }

  public static int getLatestNumBytesForDenseStorage()
  {
    return HLLCV1.NUM_BYTES_FOR_DENSE_STORAGE;
  }

  public static byte[] makeEmptyVersionedByteArray()
  {
    byte[] arr = new byte[getLatestNumBytesForDenseStorage()];
    arr[0] = HLLCV1.VERSION;
    return arr;
  }

  public static double applyCorrection(double e, int zeroCount)
  {
    e = CORRECTION_PARAMETER / e;

    if (e <= LOW_CORRECTION_THRESHOLD) {
      return zeroCount == 0 ? e : NUM_BUCKETS * Math.log(NUM_BUCKETS / (double) zeroCount);
    }

    if (e > HIGH_CORRECTION_THRESHOLD) {
      final double ratio = e / TWO_TO_THE_SIXTY_FOUR;
      if (ratio >= 1) {
        // handle very unlikely case that value is > 2^64
        return Double.MAX_VALUE;
      } else {
        return -TWO_TO_THE_SIXTY_FOUR * Math.log(1 - ratio);
      }
    }

    return e;
  }

  private static double estimateSparse(
      final ByteBuffer buf,
      final byte minNum,
      final byte overflowValue,
      final short overflowPosition,
      final boolean isUpperNibble
  )
  {
    final ByteBuffer copy = buf.asReadOnlyBuffer();
    double e = 0.0d;
    int zeroCount = NUM_BUCKETS - 2 * (buf.remaining() / 3);
    while (copy.hasRemaining()) {
      short position = copy.getShort();
      final int register = (int) copy.get() & 0xff;
      if (overflowValue != 0 && position == overflowPosition) {
        int upperNibble = ((register & 0xf0) >>> bitsPerBucket) + minNum;
        int lowerNibble = (register & 0x0f) + minNum;
        if (isUpperNibble) {
          upperNibble = Math.max(upperNibble, overflowValue);
        } else {
          lowerNibble = Math.max(lowerNibble, overflowValue);
        }
        e += 1.0d / Math.pow(2, upperNibble) + 1.0d / Math.pow(2, lowerNibble);
        zeroCount += (((upperNibble & 0xf0) == 0) ? 1 : 0) + (((lowerNibble & 0x0f) == 0) ? 1 : 0);
      } else {
        e += minNumRegisterLookup[minNum][register];
        zeroCount += numZeroLookup[register];
      }
    }

    e += zeroCount;
    return applyCorrection(e, zeroCount);
  }

  private static double estimateDense(
      final ByteBuffer buf,
      final byte minNum,
      final byte overflowValue,
      final short overflowPosition,
      final boolean isUpperNibble
  )
  {
    final ByteBuffer copy = buf.asReadOnlyBuffer();
    double e = 0.0d;
    int zeroCount = 0;
    int position = 0;
    while (copy.hasRemaining()) {
      final int register = (int) copy.get() & 0xff;
      if (overflowValue != 0 && position == overflowPosition) {
        int upperNibble = ((register & 0xf0) >>> bitsPerBucket) + minNum;
        int lowerNibble = (register & 0x0f) + minNum;
        if (isUpperNibble) {
          upperNibble = Math.max(upperNibble, overflowValue);
        } else {
          lowerNibble = Math.max(lowerNibble, overflowValue);
        }
        e += 1.0d / Math.pow(2, upperNibble) + 1.0d / Math.pow(2, lowerNibble);
        zeroCount += (((upperNibble & 0xf0) == 0) ? 1 : 0) + (((lowerNibble & 0x0f) == 0) ? 1 : 0);
      } else {
        e += minNumRegisterLookup[minNum][register];
        zeroCount += numZeroLookup[register];
      }
      position++;
    }

    return applyCorrection(e, zeroCount);
  }

  private static boolean isSparse(ByteBuffer buffer)
  {
    return buffer.remaining() != NUM_BYTES_FOR_BUCKETS;
  }

  private volatile ByteBuffer storageBuffer;
  private volatile int initPosition;
  private volatile Double estimatedCardinality;

  public HyperLogLogCollector(ByteBuffer byteBuffer)
  {
    storageBuffer = byteBuffer.duplicate();
    initPosition = byteBuffer.position();
    estimatedCardinality = null;
  }

  public abstract byte getVersion();

  public abstract void setVersion(ByteBuffer buffer);

  public abstract byte getRegisterOffset();

  public abstract void setRegisterOffset(byte registerOffset);

  public abstract void setRegisterOffset(ByteBuffer buffer, byte registerOffset);

  public abstract short getNumNonZeroRegisters();

  public abstract void setNumNonZeroRegisters(short numNonZeroRegisters);

  public abstract void setNumNonZeroRegisters(ByteBuffer buffer, short numNonZeroRegisters);

  public abstract byte getMaxOverflowValue();

  public abstract void setMaxOverflowValue(byte value);

  public abstract void setMaxOverflowValue(ByteBuffer buffer, byte value);

  public abstract short getMaxOverflowRegister();

  public abstract void setMaxOverflowRegister(short register);

  public abstract void setMaxOverflowRegister(ByteBuffer buffer, short register);

  public abstract int getNumHeaderBytes();

  public abstract int getNumBytesForDenseStorage();

  public abstract int getPayloadBytePosition();

  public abstract int getPayloadBytePosition(ByteBuffer buffer);

  protected int getInitPosition()
  {
    return initPosition;
  }

  protected ByteBuffer getStorageBuffer()
  {
    return storageBuffer;
  }

  public void add(byte[] hashedValue)
  {
    if (hashedValue.length < minBytesRequired) {
      throw new IAE("Insufficient bytes, need[%d] got [%d]", minBytesRequired, hashedValue.length);
    }

    estimatedCardinality = null;

    final ByteBuffer buffer = ByteBuffer.wrap(hashedValue);

    short bucket = (short) (buffer.getShort(hashedValue.length - 2) & bucketMask);

    byte positionOf1 = 0;

    for (int i = 0; i < 8; ++i) {
      byte lookupVal = ByteBitLookup.lookup[UnsignedBytes.toInt(hashedValue[i])];
      switch (lookupVal) {
        case 0:
          positionOf1 += 8;
          continue;
        default:
          positionOf1 += lookupVal;
          i = 8;
          break;
      }
    }

    add(bucket, positionOf1);
  }

  public void add(short bucket, byte positionOf1)
  {
    if (storageBuffer.isReadOnly()) {
      convertToMutableByteBuffer();
    }

    byte registerOffset = getRegisterOffset();

    // discard everything outside of the range we care about
    if (positionOf1 <= registerOffset) {
      return;
    } else if (positionOf1 > (registerOffset + range)) {
      byte currMax = getMaxOverflowValue();
      if (positionOf1 > currMax) {
        setMaxOverflowValue(positionOf1);
        setMaxOverflowRegister(bucket);
      }
      return;
    }

    // whatever value we add must be stored in 4 bits
    short numNonZeroRegisters = addNibbleRegister(bucket, (byte) ((0xff & positionOf1) - registerOffset));
    setNumNonZeroRegisters(numNonZeroRegisters);
    if (numNonZeroRegisters == NUM_BUCKETS) {
      setRegisterOffset(++registerOffset);
      setNumNonZeroRegisters(decrementBuckets());
    }
  }

  public HyperLogLogCollector fold(HyperLogLogCollector other)
  {
    if (other == null || other.storageBuffer.remaining() == 0) {
      return this;
    }

    if (storageBuffer.isReadOnly()) {
      convertToMutableByteBuffer();
    }

    estimatedCardinality = null;

    if (getRegisterOffset() < other.getRegisterOffset()) {
      // "Swap" the buffers so that we are folding into the one with the higher offset
      ByteBuffer newStorage = ByteBuffer.allocate(other.storageBuffer.remaining());
      newStorage.put(other.storageBuffer.asReadOnlyBuffer());
      newStorage.clear();

      other.storageBuffer = storageBuffer;
      other.initPosition = initPosition;
      storageBuffer = newStorage;
      initPosition = 0;
    }

    ByteBuffer otherBuffer = other.storageBuffer.asReadOnlyBuffer();
    byte otherOffset = other.getRegisterOffset();

    if (storageBuffer.remaining() != getNumBytesForDenseStorage()) {
      convertToDenseStorage();
    }

    byte myOffset = getRegisterOffset();
    short numNonZero = getNumNonZeroRegisters();

    int offsetDiff = myOffset - otherOffset;
    if (offsetDiff < 0) {
      throw new ISE("offsetDiff[%d] < 0, shouldn't happen because of swap.", offsetDiff);
    }

    byte otherOverflowValue = other.getMaxOverflowValue();
    short otherOverflowRegister = other.getMaxOverflowRegister();
    add(otherOverflowRegister, otherOverflowValue);

    int myPayloadStart = getPayloadBytePosition();
    otherBuffer.position(other.getPayloadBytePosition());
    if (isSparse(otherBuffer)) {
      while (otherBuffer.hasRemaining()) {
        short position = otherBuffer.getShort();
        int payloadStartPosition = position - other.getNumHeaderBytes();
        numNonZero += mergeAndStoreByteRegister(
            myPayloadStart + payloadStartPosition,
            offsetDiff,
            otherBuffer.get()
        );
        if (numNonZero == NUM_BUCKETS) {
          myOffset += 1;
          numNonZero = decrementBuckets();
          setRegisterOffset(myOffset);
          setNumNonZeroRegisters(numNonZero);

          offsetDiff = myOffset - otherOffset;
        }
      }
    } else { // dense
      int position = getPayloadBytePosition();
      while (otherBuffer.hasRemaining()) {
        numNonZero += mergeAndStoreByteRegister(
            position,
            offsetDiff,
            otherBuffer.get()
        );
        if (numNonZero == NUM_BUCKETS) {
          myOffset += 1;
          numNonZero = decrementBuckets();
          setRegisterOffset(myOffset);
          setNumNonZeroRegisters(numNonZero);

          offsetDiff = myOffset - otherOffset;
        }
        position++;
      }
    }

    setRegisterOffset(myOffset);
    setNumNonZeroRegisters(numNonZero);

    return this;
  }

  public HyperLogLogCollector fold(ByteBuffer buffer)
  {
    return fold(makeCollector(buffer));
  }

  public ByteBuffer toByteBuffer()
  {
    short numNonZeroRegisters = getNumNonZeroRegisters();

    // store sparsely
    if (storageBuffer.remaining() == getNumBytesForDenseStorage() && numNonZeroRegisters < DENSE_THRESHOLD) {
      ByteBuffer retVal = ByteBuffer.wrap(new byte[numNonZeroRegisters * 3 + getNumHeaderBytes()]);
      setVersion(retVal);
      setRegisterOffset(retVal, getRegisterOffset());
      setNumNonZeroRegisters(retVal, numNonZeroRegisters);
      setMaxOverflowValue(retVal, getMaxOverflowValue());
      setMaxOverflowRegister(retVal, getMaxOverflowRegister());

      int startPosition = getPayloadBytePosition();
      retVal.position(getPayloadBytePosition(retVal));
      for (int i = startPosition; i < startPosition + NUM_BYTES_FOR_BUCKETS; i++) {
        if (storageBuffer.get(i) != 0) {
          retVal.putShort((short) (0xffff & (i - initPosition)));
          retVal.put(storageBuffer.get(i));
        }
      }
      retVal.rewind();
      return retVal.asReadOnlyBuffer();
    }

    return storageBuffer.asReadOnlyBuffer();
  }

  @JsonValue
  public byte[] toByteArray()
  {
    final ByteBuffer buffer = toByteBuffer();
    byte[] theBytes = new byte[buffer.remaining()];
    buffer.get(theBytes);

    return theBytes;
  }

  public double estimateCardinality()
  {
    if (estimatedCardinality == null) {
      byte registerOffset = getRegisterOffset();
      byte overflowValue = getMaxOverflowValue();
      short overflowRegister = getMaxOverflowRegister();
      short overflowPosition = (short) (overflowRegister >>> 1);
      boolean isUpperNibble = ((overflowRegister & 0x1) == 0);

      storageBuffer.position(getPayloadBytePosition());

      if (isSparse(storageBuffer)) {
        estimatedCardinality = estimateSparse(
            storageBuffer,
            registerOffset,
            overflowValue,
            overflowPosition,
            isUpperNibble
        );
      } else {
        estimatedCardinality = estimateDense(
            storageBuffer,
            registerOffset,
            overflowValue,
            overflowPosition,
            isUpperNibble
        );
      }

      storageBuffer.position(initPosition);
    }
    return estimatedCardinality;
  }

  public double estimateByteBuffer(ByteBuffer buf)
  {
    return makeCollector(buf).estimateCardinality();
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    HyperLogLogCollector collector = (HyperLogLogCollector) o;

    if (storageBuffer != null ? !storageBuffer.equals(collector.storageBuffer) : collector.storageBuffer != null) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode()
  {
    int result = storageBuffer != null ? storageBuffer.hashCode() : 0;
    result = 31 * result + initPosition;
    return result;
  }

  @Override
  public String toString()
  {
    return "HyperLogLogCollector{" +
           "initPosition=" + initPosition +
           ", version=" + getVersion() +
           ", registerOffset=" + getRegisterOffset() +
           ", numNonZeroRegisters=" + getNumNonZeroRegisters() +
           ", maxOverflowValue=" + getMaxOverflowValue() +
           ", maxOverflowRegister=" + getMaxOverflowRegister() +
           '}';
  }

  private short decrementBuckets()
  {
    short count = 0;
    int startPosition = getPayloadBytePosition();
    for (int i = startPosition; i < startPosition + NUM_BYTES_FOR_BUCKETS; i++) {
      byte val = (byte) (storageBuffer.get(i) - 0x11);
      if ((val & 0xf0) != 0) {
        count++;
      }
      if ((val & 0x0f) != 0) {
        count++;
      }
      storageBuffer.put(i, val);
    }
    return count;
  }

  private void convertToMutableByteBuffer()
  {
    ByteBuffer tmpBuffer = ByteBuffer.allocate(storageBuffer.remaining());
    tmpBuffer.put(storageBuffer.asReadOnlyBuffer());
    tmpBuffer.position(0);
    storageBuffer = tmpBuffer;
    initPosition = 0;
  }

  private void convertToDenseStorage()
  {
    ByteBuffer tmpBuffer = ByteBuffer.wrap(new byte[getNumBytesForDenseStorage()]);
    // put header
    setVersion(tmpBuffer);
    setRegisterOffset(tmpBuffer, getRegisterOffset());
    setNumNonZeroRegisters(tmpBuffer, getNumNonZeroRegisters());
    setMaxOverflowValue(tmpBuffer, getMaxOverflowValue());
    setMaxOverflowRegister(tmpBuffer, getMaxOverflowRegister());

    storageBuffer.position(getPayloadBytePosition());
    tmpBuffer.position(getPayloadBytePosition(tmpBuffer));
    // put payload
    while (storageBuffer.hasRemaining()) {
      tmpBuffer.put(storageBuffer.getShort(), storageBuffer.get());
    }
    tmpBuffer.rewind();
    storageBuffer = tmpBuffer;
    initPosition = 0;
  }

  private short addNibbleRegister(short bucket, byte positionOf1)
  {
    short numNonZeroRegs = getNumNonZeroRegisters();
    final short position = (short) (bucket >> 1);
    final boolean isUpperNibble = ((bucket & 0x1) == 0);

    byte shiftedPositionOf1 = (isUpperNibble) ? (byte) (positionOf1 << bitsPerBucket) : positionOf1;

    if (storageBuffer.remaining() != getNumBytesForDenseStorage()) {
      convertToDenseStorage();
    }

    byte origVal = storageBuffer.get(getPayloadBytePosition() + position);
    byte newValueMask = (isUpperNibble) ? (byte) 0xf0 : (byte) 0x0f;
    byte originalValueMask = (byte) (newValueMask ^ 0xff);

    // if something was at zero, we have to increase the numNonZeroRegisters
    if ((origVal & newValueMask) == 0 && shiftedPositionOf1 != 0) {
      numNonZeroRegs++;
    }

    storageBuffer.put(
        getPayloadBytePosition() + position,
        (byte) (UnsignedBytes.max((byte) (origVal & newValueMask), shiftedPositionOf1) | (origVal & originalValueMask))
    );

    return numNonZeroRegs;
  }

  /**
   * Returns the number of registers that are no longer zero after the value was added
   *
   * @param position   The position into the byte buffer, this position represents two "registers"
   * @param offsetDiff The difference in offset between the byteToAdd and the current HyperLogLogCollector
   * @param byteToAdd  The byte to merge into the current HyperLogLogCollector
   *
   * @return
   */
  private int mergeAndStoreByteRegister(
      int position,
      int offsetDiff,
      byte byteToAdd
  )
  {
    if (byteToAdd == 0) {
      return 0;
    }

    byte currVal = storageBuffer.get(position);

    int upperNibble = currVal & 0xf0;
    int lowerNibble = currVal & 0x0f;

    // subtract the differences so that the nibbles align
    int otherUpper = (byteToAdd & 0xf0) - (offsetDiff << bitsPerBucket);
    int otherLower = (byteToAdd & 0x0f) - offsetDiff;

    final int newUpper = Math.max(upperNibble, otherUpper);
    final int newLower = Math.max(lowerNibble, otherLower);

    int numNoLongerZero = 0;
    if (upperNibble == 0 && newUpper > 0) {
      ++numNoLongerZero;
    }

    if (lowerNibble == 0 && newLower > 0) {
      ++numNoLongerZero;
    }

    storageBuffer.put(position, (byte) ((newUpper | newLower) & 0xff));

    return numNoLongerZero;
  }

  @Override
  public int compareTo(HyperLogLogCollector other)
  {
    final int lhsOffset = (int) this.getRegisterOffset() & 0xffff;
    final int rhsOffset = (int) other.getRegisterOffset() & 0xffff;

    if (lhsOffset == rhsOffset) {
      final int lhsNumNonZero = (int) this.getNumNonZeroRegisters() & 0xff;
      final int rhsNumNonZero = (int) this.getNumNonZeroRegisters() & 0xff;
      int retVal = Double.compare(lhsNumNonZero, rhsNumNonZero);

      if (retVal == 0) {
        retVal = Double.compare(this.estimateCardinality(), other.estimateCardinality());
      }

      return retVal;
    } else {
      return Double.compare(lhsOffset, rhsOffset);
    }
  }
}
