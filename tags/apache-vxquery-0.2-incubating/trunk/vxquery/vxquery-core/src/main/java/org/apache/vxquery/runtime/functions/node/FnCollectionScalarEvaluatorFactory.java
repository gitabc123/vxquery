/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.vxquery.runtime.functions.node;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.apache.vxquery.datamodel.accessors.TaggedValuePointable;
import org.apache.vxquery.datamodel.builders.sequence.SequenceBuilder;
import org.apache.vxquery.datamodel.values.ValueTag;
import org.apache.vxquery.exceptions.ErrorCode;
import org.apache.vxquery.exceptions.SystemException;
import org.apache.vxquery.runtime.functions.base.AbstractTaggedValueArgumentScalarEvaluator;
import org.apache.vxquery.runtime.functions.base.AbstractTaggedValueArgumentScalarEvaluatorFactory;
import org.apache.vxquery.runtime.functions.util.FunctionHelper;
import org.apache.vxquery.xmlparser.ITreeNodeIdProvider;
import org.apache.vxquery.xmlparser.TreeNodeIdProvider;
import org.xml.sax.InputSource;

import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.runtime.base.IScalarEvaluator;
import edu.uci.ics.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import edu.uci.ics.hyracks.api.context.IHyracksTaskContext;
import edu.uci.ics.hyracks.data.std.api.IPointable;
import edu.uci.ics.hyracks.data.std.primitive.UTF8StringPointable;
import edu.uci.ics.hyracks.data.std.util.ArrayBackedValueStorage;
import edu.uci.ics.hyracks.dataflow.common.comm.util.ByteBufferInputStream;

public class FnCollectionScalarEvaluatorFactory extends AbstractTaggedValueArgumentScalarEvaluatorFactory {
    private static final long serialVersionUID = 1L;

    public FnCollectionScalarEvaluatorFactory(IScalarEvaluatorFactory[] args) {
        super(args);
    }

    @Override
    protected IScalarEvaluator createEvaluator(IHyracksTaskContext ctx, IScalarEvaluator[] args)
            throws AlgebricksException {
        final ArrayBackedValueStorage abvs = new ArrayBackedValueStorage();
        final UTF8StringPointable stringp = (UTF8StringPointable) UTF8StringPointable.FACTORY.createPointable();
        final TaggedValuePointable nodep = (TaggedValuePointable) TaggedValuePointable.FACTORY.createPointable();
        final ByteBufferInputStream bbis = new ByteBufferInputStream();
        final DataInputStream di = new DataInputStream(bbis);
        final SequenceBuilder sb = new SequenceBuilder();
        final ArrayBackedValueStorage abvsFileNode = new ArrayBackedValueStorage();
        final InputSource in = new InputSource();
        final int partition = ctx.getTaskAttemptId().getTaskId().getPartition();
        final ITreeNodeIdProvider nodeIdProvider = new TreeNodeIdProvider((short) partition);

        return new AbstractTaggedValueArgumentScalarEvaluator(args) {
            @Override
            protected void evaluate(TaggedValuePointable[] args, IPointable result) throws SystemException {
                String collectionName;
                TaggedValuePointable tvp = args[0];
                // TODO add support empty sequence and no argument.
                if (tvp.getTag() != ValueTag.XS_STRING_TAG) {
                    throw new SystemException(ErrorCode.FORG0006);
                }
                tvp.getValue(stringp);
                try {
                    // Get the list of files.
                    bbis.setByteBuffer(ByteBuffer.wrap(Arrays.copyOfRange(stringp.getByteArray(),
                            stringp.getStartOffset(), stringp.getLength() + stringp.getStartOffset())), 0);
                    collectionName = di.readUTF();
                } catch (IOException e) {
                    throw new SystemException(ErrorCode.SYSE0001, e);
                }
                File collectionDirectory = new File(collectionName);
                if (!collectionDirectory.exists()) {
                    throw new RuntimeException("The collection directory (" + collectionName + ") does not exist.");
                }

                try {
                    abvs.reset();
                    sb.reset(abvs);
                    addXmlFiles(collectionDirectory);
                    sb.finish();
                    result.set(abvs);
                } catch (IOException e) {
                    throw new SystemException(ErrorCode.SYSE0001, e);
                }
            }

            private void addXmlFiles(File collectionDirectory) throws SystemException, IOException {
                for (File file : collectionDirectory.listFiles()) {
                    // Add the document node to the sequence.
                    if (FunctionHelper.readableXmlFile(file.getPath())) {
                        abvsFileNode.reset();
                        FunctionHelper.readInDocFromString(file.getPath(), in, abvsFileNode, nodeIdProvider);
                        nodep.set(abvsFileNode.getByteArray(), abvsFileNode.getStartOffset(), abvsFileNode.getLength());
                        sb.addItem(nodep);
                    } else if (file.isDirectory()) {
                        // Consider all XML file in sub directories.
                        addXmlFiles(file);
                    }
                }
            }
        };
    }
}