/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.transform;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

import com.google.common.base.Functions;
import com.google.common.collect.Ordering;
import org.apache.sysml.runtime.util.UtilFunctions;

public class DummycodeAgent extends TransformationAgent {	
	
	private static final long serialVersionUID = 5832130477659116489L;

	private int[] _dcdList = null;
	private long numCols = 0;
	
	private HashMap<Integer, HashMap<String,String>> _finalMaps = null;
	private HashMap<Integer, HashMap<String,Long>> _finalMapsCP = null;
	private int[] _binList = null;
	private int[] _numBins = null;
	
	private int[] _domainSizes = null;			// length = #of dummycoded columns
	private int[] _dcdColumnMap = null;			// to help in translating between original and dummycoded column IDs
	private long _dummycodedLength = 0;			// #of columns after dummycoded
	
	DummycodeAgent(int[] list) {
		_dcdList = list;
	}
	
	DummycodeAgent(JSONObject parsedSpec, long ncol) throws JSONException {
		numCols = ncol;
		
		if ( !parsedSpec.containsKey(TX_METHOD.DUMMYCODE.toString()) )
			return;
		
		JSONObject obj = (JSONObject) parsedSpec.get(TX_METHOD.DUMMYCODE.toString());
		JSONArray attrs = (JSONArray) obj.get(JSON_ATTRS);
			
		_dcdList = new int[attrs.size()];
		for(int i=0; i < _dcdList.length; i++) 
			_dcdList[i] = UtilFunctions.toInt(attrs.get(i));
	}
	
	public int[] dcdList() {
		return _dcdList;
	}
	
	/**
	 * Method to output transformation metadata from the mappers. 
	 * This information is collected and merged by the reducers.
	 * 
	 * @param out
	 * @throws IOException
	 * 
	 */
	@Override
	public void mapOutputTransformationMetadata(OutputCollector<IntWritable, DistinctValue> out, int taskID, TfUtils agents) throws IOException {
		// There is no metadata required for dummycode.
		// Required information is output from RecodeAgent.
		return;
	}
	
	@Override
	public void mergeAndOutputTransformationMetadata(Iterator<DistinctValue> values,
			String outputDir, int colID, FileSystem fs, TfUtils agents) throws IOException {
		// Nothing to do here
	}

	public void setRecodeMaps(HashMap<Integer, HashMap<String,String>> maps) {
		_finalMaps = maps;
	}
	
	public void setRecodeMapsCP(HashMap<Integer, HashMap<String,Long>> maps) {
		_finalMapsCP = maps;
	}
	
	public void setNumBins(int[] binList, int[] numbins) {
		_binList = binList;
		_numBins = numbins;
	}
	
	/**
	 * Method to generate dummyCodedMaps.csv, with the range of column IDs for each variable in the original data.
	 * 
	 * Each line in dummyCodedMaps.csv file is of the form: [ColID, 1/0, st, end]
	 * 		1/0 indicates if ColID is dummycoded or not
	 * 		[st,end] is the range of dummycoded column numbers for the given ColID
	 * 
	 * It also generates coltypes.csv, with the type (scale, nominal, etc.) of columns in the output.
	 * Recoded columns are of type nominal, binner columns are of type ordinal, dummycoded columns are of type 
	 * dummycoded, and the remaining are of type scale.
	 * 
	 * @param fs
	 * @param txMtdDir
	 * @param numCols
	 * @param ra
	 * @param ba
	 * @return Number of columns in the transformed data
	 * @throws IOException
	 */
	public int genDcdMapsAndColTypes(FileSystem fs, String txMtdDir, int numCols, TfUtils agents) throws IOException {
		
		// initialize all column types in the transformed data to SCALE
		ColumnTypes[] ctypes = new ColumnTypes[(int) _dummycodedLength];
		for(int i=0; i < _dummycodedLength; i++)
			ctypes[i] = ColumnTypes.SCALE;
		
		_dcdColumnMap = new int[numCols];

		Path pt=new Path(txMtdDir+"/Dummycode/" + DCD_FILE_NAME);
		BufferedWriter br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
		
		int sum=1;
		int idx = 0;
		for(int colID=1; colID <= numCols; colID++) 
		{
			if ( _dcdList != null && idx < _dcdList.length && _dcdList[idx] == colID )
			{
				br.write(colID + "," + "1" + "," + sum + "," + (sum+_domainSizes[idx]-1) + "\n");
				_dcdColumnMap[colID-1] = (sum+_domainSizes[idx]-1)-1;

				for(int i=sum; i <=(sum+_domainSizes[idx]-1); i++)
					ctypes[i-1] = ColumnTypes.DUMMYCODED;
				
				sum += _domainSizes[idx];
				idx++;
			}
			else 
			{
				br.write(colID + "," + "0" + "," + sum + "," + sum + "\n");
				_dcdColumnMap[colID-1] = sum-1;
				
				if ( agents.getBinAgent().isBinned(colID) != -1 )
					ctypes[sum-1] = ColumnTypes.ORDINAL;	// binned variable results in an ordinal column
				
				if ( agents.getRecodeAgent().isRecoded(colID) != -1 )
					ctypes[sum-1] = ColumnTypes.NOMINAL;
				
				sum += 1;
			}
		}
		br.close();

		// Write coltypes.csv
		pt=new Path(txMtdDir+"/" + COLTYPES_FILE_NAME);
		br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
		
		br.write(columnTypeToID(ctypes[0]) + "");
		for(int i = 1; i < _dummycodedLength; i++) 
			br.write( "," + columnTypeToID(ctypes[i]));
		br.close();
		
		return sum-1;
	}
	
	/**
	 * Given a dummycoded column id, find the corresponding original column ID.
	 *  
	 * @param colID
	 * @return
	 */
	public int mapDcdColumnID(int colID) 
	{
		for(int i=0; i < _dcdColumnMap.length; i++)
		{
			int st = (i==0 ? 1 : _dcdColumnMap[i-1]+1+1);
			int end = _dcdColumnMap[i]+1;
			//System.out.println((i+1) + ": " + "[" + st + "," + end + "]");
			
			if ( colID >= st && colID <= end)
				return i+1;
		}
		return -1;
	}
	
	public String constructDummycodedHeader(String header, Pattern delim) {
		
		if(_dcdList == null && _binList == null )
			// none of the columns are dummycoded, simply return the given header
			return header;
		
		String[] names = delim.split(header, -1);
		List<String> newNames = null;
		
		StringBuilder sb = new StringBuilder();
		
		// Dummycoding can be performed on either on a recoded column or on a binned column
		
		// process recoded columns
		if(_finalMapsCP != null && _dcdList != null) 
		{
			for(int i=0; i <_dcdList.length; i++) 
			{
				int colID = _dcdList[i];
				HashMap<String,Long> map = _finalMapsCP.get(colID);
				String colName = UtilFunctions.unquote(names[colID-1]);
				
				if ( map != null  ) 
				{
					// order map entries by their recodeID
					Ordering<String> valueComparator = Ordering.natural().onResultOf(Functions.forMap(map));
					newNames = valueComparator.sortedCopy(map.keySet());
					
					// construct concatenated string of map entries
					sb.setLength(0);
					for(int idx=0; idx < newNames.size(); idx++) 
					{
						if(idx==0) 
							sb.append( colName + DCD_NAME_SEP + newNames.get(idx));
						else
							sb.append( delim + colName + DCD_NAME_SEP + newNames.get(idx));
					}
					names[colID-1] = sb.toString();			// replace original column name with dcd name
				}
			}
		}
		else if(_finalMaps != null && _dcdList != null) {
			for(int i=0; i <_dcdList.length; i++) {
				int colID = _dcdList[i];
				HashMap<String,String> map = _finalMaps.get(colID);
				String colName = UtilFunctions.unquote(names[colID-1]);
				
				if ( map != null ) 
				{
					// order map entries by their recodeID (represented as Strings .. "1", "2", etc.)
					Ordering<String> orderByID = new Ordering<String>() 
					{
			    		public int compare(String s1, String s2) {
			        		return (Integer.parseInt(s1) - Integer.parseInt(s2));
			    		}
					};
					
					newNames = orderByID.onResultOf(Functions.forMap(map)).sortedCopy(map.keySet());
					// construct concatenated string of map entries
					sb.setLength(0);
					for(int idx=0; idx < newNames.size(); idx++) 
					{
						if(idx==0) 
							sb.append( colName + DCD_NAME_SEP + newNames.get(idx));
						else
							sb.append( delim + colName + DCD_NAME_SEP + newNames.get(idx));
					}
					names[colID-1] = sb.toString();			// replace original column name with dcd name
				}
			}
		}
		
		// process binned columns
		if (_binList != null) 
			for(int i=0; i < _binList.length; i++) 
			{
				int colID = _binList[i];
				
				// need to consider only binned and dummycoded columns
				if(isDummyCoded(colID) == -1)
					continue;
				
				int numBins = _numBins[i];
				String colName = UtilFunctions.unquote(names[colID-1]);
				
				sb.setLength(0);
				for(int idx=0; idx < numBins; idx++) 
					if(idx==0) 
						sb.append( colName + DCD_NAME_SEP + "Bin" + (idx+1) );
					else
						sb.append( delim + colName + DCD_NAME_SEP + "Bin" + (idx+1) );
				names[colID-1] = sb.toString();			// replace original column name with dcd name
			}
		
		// Construct the full header
		sb.setLength(0);
		for(int colID=0; colID < names.length; colID++) 
		{
			if (colID == 0)
				sb.append(names[colID]);
			else
				sb.append(delim + names[colID]);
		}
		//System.out.println("DummycodedHeader: " + sb.toString());
		
		return sb.toString();
	}
	
	@Override
	public void loadTxMtd(JobConf job, FileSystem fs, Path txMtdDir, TfUtils agents) throws IOException {
		if ( _dcdList == null )
		{
			_dummycodedLength = numCols;
			return;
		}
		
		// sort to-be dummycoded column IDs in ascending order. This is the order in which the new dummycoded record is constructed in apply() function.
		Arrays.sort(_dcdList);	
		_domainSizes = new int[_dcdList.length];

		_dummycodedLength = numCols;
		
		//HashMap<String, String> map = null;
		for(int i=0; i<_dcdList.length; i++) {
			int colID = _dcdList[i];
			
			// Find the domain size for colID using _finalMaps or _finalMapsCP
			int domainSize = 0;
			if(_finalMaps != null) {
				if(_finalMaps.get(colID) != null)
					domainSize = _finalMaps.get(colID).size();
			}
			else {
				if(_finalMapsCP.get(colID) != null)
					domainSize = _finalMapsCP.get(colID).size();
			}
			
			if ( domainSize != 0 ) {
				// dummycoded column
				_domainSizes[i] = domainSize;
			}
			else {
				// binned column
				if ( _binList != null )
				for(int j=0; j<_binList.length; j++) {
					if (colID == _binList[j]) {
						_domainSizes[i] = _numBins[j];
						break;
					}
				}
			}
			_dummycodedLength += _domainSizes[i]-1;
			//System.out.println("colID=" + colID + ", domainsize=" + _domainSizes[i] + ", dcdLength=" + _dummycodedLength);
		}
	}

	/**
	 * Method to apply transformations.
	 * 
	 * @param words
	 * @return
	 */
	@Override
	public String[] apply(String[] words, TfUtils agents) {
		
		if ( _dcdList == null )
			return words;
		
		String[] nwords = new String[(int)_dummycodedLength];
		
		int rcdVal = 0;
		
		for(int colID=1, idx=0, ncolID=1; colID <= words.length; colID++) {
			if(idx < _dcdList.length && colID==_dcdList[idx]) {
				// dummycoded columns
				try {
				rcdVal = UtilFunctions.parseToInt(UtilFunctions.unquote(words[colID-1]));
				nwords[ ncolID-1+rcdVal-1 ] = "1";
				ncolID += _domainSizes[idx];
				idx++;
				} catch (Exception e) {
					System.out.println("Error in dummycoding: colID="+colID + ", rcdVal=" + rcdVal+", word="+words[colID-1] + ", domainSize=" + _domainSizes[idx] + ", dummyCodedLength=" + _dummycodedLength);
					throw new RuntimeException(e);
				}
			}
			else {
				nwords[ncolID-1] = words[colID-1];
				ncolID++;
			}
		}
		
		return nwords;
	}
	
	/**
	 * Check if the given column ID is subjected to this transformation.
	 * 
	 */
	public int isDummyCoded(int colID)
	{
		if(_dcdList == null)
			return -1;
		
		int idx = Arrays.binarySearch(_dcdList, colID);
		return ( idx >= 0 ? idx : -1);
	}
	
	@Override
	public void print() {
		System.out.print("Dummycoding List: \n    ");
		for(int i : _dcdList) {
			System.out.print(i + " ");
		}
		System.out.println();
	}

}
