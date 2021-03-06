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

package org.apache.sysml.hops.rewrite;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.hops.BinaryOp;
import org.apache.sysml.hops.DataOp;
import org.apache.sysml.hops.Hop;
import org.apache.sysml.hops.Hop.DataOpTypes;
import org.apache.sysml.hops.Hop.OpOp2;
import org.apache.sysml.hops.Hop.VisitStatus;
import org.apache.sysml.hops.HopsException;
import org.apache.sysml.hops.LiteralOp;
import org.apache.sysml.hops.UnaryOp;
import org.apache.sysml.hops.recompile.Recompiler;
import org.apache.sysml.lops.Lop;
import org.apache.sysml.lops.LopsException;
import org.apache.sysml.lops.compile.Dag;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.DMLUnsupportedOperationException;
import org.apache.sysml.runtime.controlprogram.Program;
import org.apache.sysml.runtime.controlprogram.ProgramBlock;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysml.runtime.instructions.Instruction;
import org.apache.sysml.runtime.instructions.cp.ScalarObject;

/**
 * Rule: Constant Folding. For all statement blocks, 
 * eliminate simple binary expressions of literals within dags by 
 * computing them and replacing them with a new Literal op once.
 * For the moment, this only applies within a dag, later this should be 
 * extended across statements block (global, inter-procedure). 
 */
public class RewriteConstantFolding extends HopRewriteRule
{
	
	private static final String TMP_VARNAME = "__cf_tmp";
	
	//reuse basic execution runtime
	private static ProgramBlock     _tmpPB = null;
	private static ExecutionContext _tmpEC = null;
	
	
	@Override
	public ArrayList<Hop> rewriteHopDAGs(ArrayList<Hop> roots, ProgramRewriteStatus state) 
		throws HopsException 
	{
		if( roots == null )
			return null;

		for( int i=0; i<roots.size(); i++ )
		{
			Hop h = roots.get(i);
			roots.set(i, rule_ConstantFolding(h));
		}
		
		return roots;
	}

	@Override
	public Hop rewriteHopDAG(Hop root, ProgramRewriteStatus state) 
		throws HopsException 
	{
		if( root == null )
			return null;

		return rule_ConstantFolding(root);
	}
	

	/**
	 * 
	 * @param hop
	 * @throws HopsException
	 */
	private Hop rule_ConstantFolding( Hop hop ) 
		throws HopsException 
	{
		return rConstantFoldingExpression(hop);
	}
	
	/**
	 * 
	 * @param root
	 * @throws HopsException
	 */
	private Hop rConstantFoldingExpression( Hop root ) 
		throws HopsException
	{
		if( root.getVisited() == VisitStatus.DONE )
			return root;
		
		//recursively process childs (before replacement to allow bottom-recursion)
		//no iterator in order to prevent concurrent modification
		for( int i=0; i<root.getInput().size(); i++ )
		{
			Hop h = root.getInput().get(i);
			rConstantFoldingExpression(h);
		}
		
		LiteralOp literal = null;
		
		//fold binary op if both are literals / unary op if literal
		if(    root.getDataType() == DataType.SCALAR //scalar ouput
			&& ( isApplicableBinaryOp(root) || isApplicableUnaryOp(root) ) )	
		{ 
			//core constant folding via runtime instructions
			try {
				literal = evalScalarOperation(root); 
			}
			catch(Exception ex)
			{
				LOG.error("Failed to execute constant folding instructions. No abort.", ex);
			}
			
		}
		//fold conjunctive predicate if at least one input is literal 'false'
		else if( isApplicableFalseConjunctivePredicate(root) )
		{
			literal = new LiteralOp(false);
		}
		//fold disjunctive predicate if at least one input is literal 'true'
		else if( isApplicableTrueDisjunctivePredicate(root) )
		{
			literal = new LiteralOp(true);
		}
									
		//replace binary operator with folded constant
		if( literal != null ) 
		{
			//reverse replacement in order to keep common subexpression elimination
			int plen = root.getParent().size();
			if( plen > 0 ) //broot is NOT a DAG root
			{
				for( int i=0; i<root.getParent().size(); i++ ) //for all parents
				{
					Hop parent = root.getParent().get(i);
					for( int j=0; j<parent.getInput().size(); j++ )
					{
						Hop child = parent.getInput().get(j);
						if( root == child )
						{
							//replace operator
							//root to parent link cannot be removed within this loop, as loop iterates over list containing parents.
							parent.getInput().remove(j);
							HopRewriteUtils.addChildReference(parent, literal,j);
						}
					}
				}
				root.getParent().clear();
			}
			else //broot IS a DAG root
			{
				root = literal;
			}
		}		
			
		
		//mark processed
		root.setVisited( VisitStatus.DONE );
		return root;
	}
	
	/**
	 * In order to (1) prevent unexpected side effects from constant folding and
	 * (2) for simplicity with regard to arbitrary value type combinations,
	 * we use the same compilation and runtime for constant folding as we would 
	 * use for actual instruction execution. 
	 * 
	 * @return
	 * @throws IOException 
	 * @throws DMLUnsupportedOperationException 
	 * @throws LopsException 
	 * @throws DMLRuntimeException 
	 * @throws HopsException 
	 */
	private LiteralOp evalScalarOperation( Hop bop ) 
		throws LopsException, DMLRuntimeException, DMLUnsupportedOperationException, IOException, HopsException
	{
		//Timing time = new Timing( true );
		
		DataOp tmpWrite = new DataOp(TMP_VARNAME, bop.getDataType(), bop.getValueType(), bop, DataOpTypes.TRANSIENTWRITE, TMP_VARNAME);
		
		//generate runtime instruction
		Dag<Lop> dag = new Dag<Lop>();
		Recompiler.rClearLops(tmpWrite); //prevent lops reuse
		Lop lops = tmpWrite.constructLops(); //reconstruct lops
		lops.addToDag( dag );	
		ArrayList<Instruction> inst = dag.getJobs(null, ConfigurationManager.getConfig());
		
		//execute instructions
		ExecutionContext ec = getExecutionContext();
		ProgramBlock pb = getProgramBlock();
		pb.setInstructions( inst );
		
		pb.execute( ec );
		
		//get scalar result (check before invocation)
		ScalarObject so = (ScalarObject) ec.getVariable(TMP_VARNAME);
		LiteralOp literal = null;
		switch( bop.getValueType() ){
			case DOUBLE:  literal = new LiteralOp(so.getDoubleValue()); break;
			case INT:     literal = new LiteralOp(so.getLongValue()); break;
			case BOOLEAN: literal = new LiteralOp(so.getBooleanValue()); break;
			case STRING:  literal = new LiteralOp(so.getStringValue()); break;	
			default:
				throw new HopsException("Unsupported literal value type: "+bop.getValueType());
		}
		
		//cleanup
		tmpWrite.getInput().clear();
		bop.getParent().remove(tmpWrite);
		pb.setInstructions(null);
		ec.getVariables().removeAll();
		
		//set literal properties (scalar)
 		literal.setDim1(0);
		literal.setDim2(0);
		literal.setRowsInBlock(-1);
		literal.setColsInBlock(-1);
		
		//System.out.println("Constant folded in "+time.stop()+"ms.");
		
		return literal;
	}
	
	/**
	 * 
	 * @return
	 * @throws DMLRuntimeException
	 */
	private static ProgramBlock getProgramBlock() 
		throws DMLRuntimeException
	{
		if( _tmpPB == null )
			_tmpPB = new ProgramBlock( new Program() );
		return _tmpPB;
	}
	
	/**
	 * 
	 * @return
	 */
	private static ExecutionContext getExecutionContext()
	{
		if( _tmpEC == null )
			_tmpEC = ExecutionContextFactory.createContext();
		return _tmpEC;
	}
	
	/**
	 * 
	 * @param hop
	 * @return
	 */
	private boolean isApplicableBinaryOp( Hop hop )
	{
		ArrayList<Hop> in = hop.getInput();
		return (   hop instanceof BinaryOp 
				&& in.get(0) instanceof LiteralOp 
				&& in.get(1) instanceof LiteralOp
				&& ((BinaryOp)hop).getOp()!=OpOp2.CBIND
				&& ((BinaryOp)hop).getOp()!=OpOp2.RBIND);
		
		//string append is rejected although possible because it
		//messes up the explain runtime output due to introduced \n 
	}
	
	/**
	 * 
	 * @param hop
	 * @return
	 */
	private boolean isApplicableUnaryOp( Hop hop )
	{
		ArrayList<Hop> in = hop.getInput();
		return (   hop instanceof UnaryOp 
				&& in.get(0) instanceof LiteralOp 
				&& HopRewriteUtils.isValueTypeCast(((UnaryOp)hop).getOp()));			
	}
	
	/**
	 * 
	 * @param hop
	 * @return
	 * @throws HopsException
	 */
	private boolean isApplicableFalseConjunctivePredicate( Hop hop ) 
		throws HopsException
	{
		ArrayList<Hop> in = hop.getInput();
		return (   hop instanceof BinaryOp 
				&& ((BinaryOp)hop).getOp()==OpOp2.AND
				&& ( (in.get(0) instanceof LiteralOp && !((LiteralOp)in.get(0)).getBooleanValue())   
				   ||(in.get(1) instanceof LiteralOp && !((LiteralOp)in.get(1)).getBooleanValue())) );			
	}
	
	/**
	 * 
	 * @param hop
	 * @return
	 * @throws HopsException
	 */
	private boolean isApplicableTrueDisjunctivePredicate( Hop hop ) 
		throws HopsException
	{
		ArrayList<Hop> in = hop.getInput();
		return (   hop instanceof BinaryOp 
				&& ((BinaryOp)hop).getOp()==OpOp2.OR
				&& ( (in.get(0) instanceof LiteralOp && ((LiteralOp)in.get(0)).getBooleanValue())   
				   ||(in.get(1) instanceof LiteralOp && ((LiteralOp)in.get(1)).getBooleanValue())) );			
	}
}
