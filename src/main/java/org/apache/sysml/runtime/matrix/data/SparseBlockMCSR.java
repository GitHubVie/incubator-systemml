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


package org.apache.sysml.runtime.matrix.data;

/**
 * SparseBlock implementation that realizes a 'modified compressed sparse row'
 * representation, where each compressed row is stored as a separate SparseRow
 * object which provides flexibility for unsorted row appends without the need 
 * for global reshifting of values/indexes but it incurs additional memory 
 * overhead per row for object/array headers per row which also slows down
 * memory-bound operations due to higher memory bandwidth requirements.
 * 
 */
public class SparseBlockMCSR extends SparseBlock
{
	private static final long serialVersionUID = -4743624499258436199L;
	
	private SparseRow[] _rows = null;
	
	/**
	 * Copy constructor old sparse row representation. 
	 */
	public SparseBlockMCSR(SparseRow[] rows, boolean deep) {
		if( deep ) {
			_rows = new SparseRow[rows.length];
			for( int i=0; i<_rows.length; i++ )
				_rows[i] = new SparseRow(rows[i]);
		}
		else {
			_rows = rows;	
		}
	}
	
	public SparseBlockMCSR(int rlen, int clen) {
		_rows = new SparseRow[rlen];
	}

	///////////////////
	//SparseBlock implementation

	@Override
	public void allocate(int r) {
		if( _rows[r] == null )
			_rows[r] = new SparseRow();
	}
	
	@Override
	public int numRows() {
		return _rows.length;
	}

	@Override
	public boolean isThreadSafe() {
		return true;
	}

	@Override 
	public void reset() {
		for( SparseRow row : _rows )
			if( row != null )
				row.reset(row.size(), -1);
	}
	
	@Override
	public long size() {
		//recompute non-zeros to avoid redundant maintenance
		long nnz = 0;
		for( SparseRow row : _rows )
			if( row != null ) 
				nnz += row.size();
		return nnz;
	}

	@Override
	public int size(int r) {
		//prior check with isEmpty(r) expected
		return _rows[r].size();
	}
	
	@Override
	public long size(int rl, int ru) {
		int ret = 0;
		for( int i=rl; i<ru; i++ )
			ret += (_rows[i]!=null) ? _rows[i].size() : 0;		
		return ret;
	}
	
	@Override
	public long size(int rl, int ru, int cl, int cu) {
		long nnz = 0;
		for(int i=rl; i<ru; i++)
			if( !isEmpty(i) ) {
				int start = posFIndexGTE(i, cl);
				int end = posFIndexGTE(i, cu);
				nnz += (start!=-1) ? (end-start) : 0;
			}
		return nnz;
	}

	@Override
	public boolean isEmpty(int r) {
		return (_rows[r]==null || _rows[r].isEmpty());
	}
	
	@Override
	public int[] indexes(int r) {
		//prior check with isEmpty(r) expected
		return _rows[r].indexes();
	}

	@Override
	public double[] values(int r) {
		//prior check with isEmpty(r) expected
		return _rows[r].values();
	}

	@Override
	public int pos(int r) {
		//arrays per row (always start 0)
		return 0;
	}

	@Override
	public boolean set(int r, int c, double v) {
		if( _rows[r] == null )
			_rows[r] = new SparseRow();
		return _rows[r].set(c, v);
	}

	@Override
	public void append(int r, int c, double v) {
		if( _rows[r] == null )
			_rows[r] = new SparseRow();
		_rows[r].append(c, v);
	}

	@Override
	public void setIndexRange(int r, int cl, int cu, double[] v, int vix, int len) {
		if( _rows[r] == null )
			_rows[r] = new SparseRow();
		//different sparse row semantics: upper bound inclusive
		_rows[r].setIndexRange(cl, cu-1, v, vix, len);
	}

	@Override
	public void deleteIndexRange(int r, int cl, int cu) {
		//prior check with isEmpty(r) expected
		//different sparse row semantics: upper bound inclusive
		_rows[r].deleteIndexRange(cl, cu-1);
	}

	@Override
	public void sort() {
		for( SparseRow row : _rows )
			if( row != null && !row.isEmpty() )
				row.sort();
	}

	@Override
	public void sort(int r) {
		//prior check with isEmpty(r) expected
		_rows[r].sort();
	}

	@Override
	public double get(int r, int c) {
		//prior check with isEmpty(r) expected
		return _rows[r].get(c); 
	}

	@Override
	public int posFIndexLTE(int r, int c) {
		//prior check with isEmpty(r) expected
		return _rows[r].searchIndexesFirstLTE(c);
	}

	@Override
	public int posFIndexGTE(int r, int c) {
		//prior check with isEmpty(r) expected
		return _rows[r].searchIndexesFirstGTE(c);
	}

	@Override
	public int posFIndexGT(int r, int c) {
		//prior check with isEmpty(r) expected
		return _rows[r].searchIndexesFirstGT(c);
	}
}
