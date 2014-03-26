/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2013
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.test.components.runtime.matrix.operations;


import org.junit.Test;


public class AggregateTest
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2013\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
    @Test
    public void testParseOperation() {
       /*
        try {
            assertEquals(Aggregate.SupportedOperation.AGG_SUMATION, Aggregate.parseOperation("a+"));
            assertEquals(Aggregate.SupportedOperation.AGG_PRODUCT, Aggregate.parseOperation("a*"));
            assertEquals(Aggregate.SupportedOperation.AGG_MINIMIZATION, Aggregate.parseOperation("amin"));
            assertEquals(Aggregate.SupportedOperation.AGG_MAXIMIZATION, Aggregate.parseOperation("amax"));
        } catch(DMLUnsupportedOperationException e) {
            fail("Operation parsing failed");
        }
        try {
            Aggregate.parseOperation("wrong");
            fail("Wrong operation gets parsed");
        } catch(DMLUnsupportedOperationException e) { }
        */
    }

    @Test
    public void testParseInstruction() {
      /*  try {
            AggregateInstruction instType = (AggregateInstruction) AggregateInstruction.parseInstruction("a+ 0 1");
            //assertEquals(Aggregate.SupportedOperation.AGG_SUMATION, instType.operation);
            assertEquals(0, instType.input);
            assertEquals(1, instType.output);

            instType = (AggregateInstruction) AggregateInstruction.parseInstruction("a* 0 1");
            //assertEquals(Aggregate.SupportedOperation.AGG_PRODUCT, instType.operation);
            assertEquals(0, instType.input);
            assertEquals(1, instType.output);

            instType = (AggregateInstruction) AggregateInstruction.parseInstruction("amin 0 1");
            //assertEquals(Aggregate.SupportedOperation.AGG_MINIMIZATION, instType.operation);
            assertEquals(0, instType.input);
            assertEquals(1, instType.output);

            instType = (AggregateInstruction) AggregateInstruction.parseInstruction("amax 0 1");
            //assertEquals(Aggregate.SupportedOperation.AGG_MAXIMIZATION, instType.operation);
            assertEquals(0, instType.input);
            assertEquals(1, instType.output);
        } catch (DMLException e) {
            fail("Instruction parsing failed");
        }*/
    }

}