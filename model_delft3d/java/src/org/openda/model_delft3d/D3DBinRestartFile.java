package org.openda.model_delft3d;

import ucar.ma2.Array;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import java.nio.ByteBuffer;

/**
 * Created by hummel on 20-Apr-16.
 */
public class D3DBinRestartFile {

	private final int mMax;
	private final int nMax;
	private final int nLay;
	private final int nSubstances;
	private File binRestartFilePath;
	private RandomAccessFile randomAccessFile;
	private int valueSize = 4; // Values in binary files are floats (4 bytes/number)
	private int recLenIndicatorSize = 4;
	private int recordDividerSize = 4;

	D3DBinRestartFile(File binRestartFilePath, int mMax, int nMax, int nLay, int nSubstances) {
		this.binRestartFilePath = binRestartFilePath;
		this.mMax = mMax;
		this.nMax = nMax;
		this.nLay = nLay;
		this.nSubstances = nSubstances;
	}

	public void open(){
		try {
			randomAccessFile = new RandomAccessFile(binRestartFilePath, "rw");
		} catch (FileNotFoundException e) {
			throw new RuntimeException("Could not find binary restart file " + binRestartFilePath.getAbsolutePath());
		}
	}

	public void write(String varName, double[] values){

		if (nSubstances != 1){
			throw new RuntimeException("Only one substance (temp or salinity) supported for writing into binary file. Current number of substances= " + nSubstances);
		}

		double[] valuesInRestartBinOrder = new double[values.length];
		int actualNLay = varName.equals("S1") ? 1 : nLay;
		for (int lay=0; lay < actualNLay; lay++) {
			for (int n=0; n < nMax; n++) {
				for (int m=0; m < mMax; m++) {
					int ncIndex = n + nMax * m + mMax * nMax * lay;
					int binIndex = m + mMax * n + mMax * nMax * lay;
					if (!Double.isNaN(values[ncIndex])) {
						valuesInRestartBinOrder[binIndex] = values[ncIndex];
 					}
					else {
						valuesInRestartBinOrder[binIndex] = 0d;
					}
				}
			}
		}

		long [] positionAndSize; // positionAndSize[0]: positionAndSize[1]: size
		int valueSize = this.valueSize;
		positionAndSize = getPositionAndSize(varName, valueSize);

		try {
			byte[] allBytes = new byte[(int) positionAndSize[1]];

			for (int lay = 0; lay < actualNLay; lay++) {
				int index = 0;
				for (int j = 0; j < valuesInRestartBinOrder.length/actualNLay; j++) {
					float value = (float) valuesInRestartBinOrder[j+lay*mMax*nMax];
					byte[] floatAsBytes = float2ByteArray(value);
					for (int i = 0; i < valueSize; i++) {
						allBytes[index] = floatAsBytes[i];
						index++;
					}
				}
				randomAccessFile.seek(positionAndSize[0] + lay*(positionAndSize[1] + this.recLenIndicatorSize + this.recordDividerSize));
				randomAccessFile.write(allBytes);
			}
		} catch (IOException e) {
			throw new RuntimeException("Could not jump through binary restart file " + binRestartFilePath.getAbsolutePath());
		}
	}


	public float[] read(String varName) {

		long [] positionAndSize; // positionAndSize[0]: positionAndSize[1]: size
		int valueSize = this.valueSize;
		positionAndSize = getPositionAndSize(varName, valueSize);

		int varLay = varName.equals("S1") ? 1 : nLay;

		float[] values = new float[(int)(positionAndSize[1]*varLay/valueSize)];

		try {
			byte[] allBytes = new byte[(int) positionAndSize[1]];
			int k = 0;
			for (int lay = 0; lay < varLay; lay++) {
				randomAccessFile.seek(positionAndSize[0] + lay*(positionAndSize[1] + this.recLenIndicatorSize + this.recordDividerSize));
				randomAccessFile.read(allBytes);
				int byteIndex = 0;
				for (int j = 0; j < values.length/varLay; j++) {
					byte[] floatAsBytes = new byte[valueSize];
					for (int i = 0; i < valueSize; i++) {
						floatAsBytes[i] = allBytes[byteIndex];
						byteIndex++;
					}
					values[k] = toFloat(floatAsBytes);
					k++;
				}
			}

		} catch (IOException e) {
			throw new RuntimeException("Could not jump through binary restart file " + binRestartFilePath.getAbsolutePath());
		}
		return values;
	}

	public void close(){
		try {
			randomAccessFile.close();
		} catch (IOException e) {
			throw new RuntimeException("Could not close binary restart file " + binRestartFilePath.getAbsolutePath());
		}
	}

	private long[] getPositionAndSize(String varName, int valueSize) {

		long[] positionAndSize = new long[2];

		int recLenIndicatorSize = this.recLenIndicatorSize;
		int recordDividerSize = this.recordDividerSize;

		// I decided to work per layers, hence positionAndSize[1] will be the bytes per layer, same for all variables
		positionAndSize[1] = mMax*nMax*valueSize;

		if (varName.equals("S1")){
			positionAndSize[0] = recLenIndicatorSize; // after first rec length
//			positionAndSize[1] = mMax*nMax*valueSize;
		}else if (varName.equals("U1")){
			positionAndSize[0] = 2 * recLenIndicatorSize + mMax*nMax*valueSize + recordDividerSize;
//			positionAndSize[0] = 2 * recLenIndicatorSize + recordDividerSize + mMax*nMax*valueSize;
//			positionAndSize[1] = mMax*nMax*nLay*valueSize;
		}else if (varName.equals("V1")){
//			positionAndSize[0] = 3 * recLenIndicatorSize + 2 * recordDividerSize + mMax*nMax*nLay*valueSize;
//			positionAndSize[1] = mMax*nMax*nLay*valueSize;
			positionAndSize[0] = (2+nLay) * recLenIndicatorSize + (1+nLay)*recordDividerSize + mMax*nMax*valueSize + mMax*nMax*nLay*valueSize;
		}else if (varName.equals("R1")){
//			positionAndSize[0] = 4 * recLenIndicatorSize + 3 * recordDividerSize + 2*mMax*nMax*nLay*valueSize;
//			positionAndSize[1] = mMax*nMax*nLay*nSubstances*valueSize;
			positionAndSize[0] = (2+2*nLay) * recLenIndicatorSize + (1+2*nLay)*recordDividerSize + mMax*nMax*valueSize + 2*mMax*nMax*nLay*valueSize;
		}else{
			throw new RuntimeException("Only S1, U1 V1 and R1 are supported for writing into the binary restart file");
		}
		return positionAndSize;
	}

	private static byte[] float2ByteArray(float value) {
//		byte[] bytes = new byte[4];
//		ByteBuffer.wrap(bytes).putFloat(value);
//		return bytes;
		byte[] bytes = ByteBuffer.allocate(4).putFloat(value).array();
		return swapBytes(bytes);
	}

	private static float toFloat(byte[] bytes) {
		byte[] swappedBytes = swapBytes(bytes);
		return ByteBuffer.wrap(swappedBytes).getFloat();
	}

	private static byte[] swapBytes(byte[] bytes) {
		int length = bytes.length;
		byte[] swappedBytes = new byte[length];
		for (int i = 0; i < length; i++) {
			swappedBytes[i] = bytes[length-i-1];
		}
		return swappedBytes;
	}

}