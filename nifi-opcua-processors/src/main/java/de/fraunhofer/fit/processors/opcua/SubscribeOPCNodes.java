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
package de.fraunhofer.fit.processors.opcua;

import de.fraunhofer.fit.opcua.OPCUAService;
import de.fraunhofer.fit.processors.opcua.utils.RecordAggregator;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

@Tags({"opc"})
@CapabilityDescription("Subscribe to a list of nodes and output flowfiles when changes are detected.")
public class SubscribeOPCNodes extends AbstractProcessor {

    private OPCUAService opcUaService;
    private BlockingQueue<String> msgQueue;
    private List<String> tagNames;
    private String subscriberUid;
    private boolean aggregateRecord;
    private boolean tsChangedNotify;
    private long minPublishInterval;
    private RecordAggregator recordAggregator;

    public static final PropertyDescriptor OPCUA_SERVICE = new PropertyDescriptor.Builder()
            .name("OPC UA Service")
            .description("Specifies the OPC UA Service that can be used to access data")
            .required(true)
            .identifiesControllerService(OPCUAService.class)
            .build();

    public static final PropertyDescriptor TAG_FILE_LOCATION = new PropertyDescriptor
            .Builder().name("Tag List File Location")
            .description("The location of the tag list file")
            .required(true)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .sensitive(false)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor AGGREGATE_RECORD = new PropertyDescriptor
            .Builder().name("Aggregate Records")
            .description("Whether to aggregate records. If this is set to true, then variable with the same time stamp will be merged into a single line. This is useful for batch-based data.")
            .required(true)
            .defaultValue("false")
            .allowableValues("true", "false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .sensitive(false)
            .build();

    public static final PropertyDescriptor TS_CHANGE_NOTIFY = new PropertyDescriptor
            .Builder().name("Notified when Timestamp changed")
            .description("Whether the data should be collected, when only the timestamp of a variable has changed, but not its value.")
            .required(true)
            .defaultValue("true")
            .allowableValues("true", "false")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .sensitive(false)
            .build();

    public static final PropertyDescriptor MIN_PUBLISH_INTERVAL = new PropertyDescriptor
            .Builder().name("Minimum publish interval of subscription notification messages")
            .description("The minimum publish interval of subscription notification messages. Set this property to a lower value so that rapid change of data can be detected.")
            .required(true)
            .defaultValue("1000")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final Relationship SUCCESS = new Relationship.Builder()
            .name("Success")
            .description("Successful OPC read")
            .build();

    public static final Relationship FAILURE = new Relationship.Builder()
            .name("Failure")
            .description("Failed OPC read")
            .build();

    public List<PropertyDescriptor> descriptors;

    public Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(OPCUA_SERVICE);
        descriptors.add(TAG_FILE_LOCATION);
        descriptors.add(AGGREGATE_RECORD);
        descriptors.add(TS_CHANGE_NOTIFY);
        descriptors.add(MIN_PUBLISH_INTERVAL);

        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(SUCCESS);
        relationships.add(FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);

        msgQueue = new LinkedBlockingQueue<>();
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {

        opcUaService = context.getProperty(OPCUA_SERVICE)
                .asControllerService(OPCUAService.class);


        try {
            tagNames = parseFile(Paths.get(context.getProperty(TAG_FILE_LOCATION).evaluateAttributeExpressions().toString()));
        } catch (IOException e) {
            getLogger().error("Error reading tag list from local file.");
            return;
        }

        aggregateRecord = Boolean.valueOf(context.getProperty(AGGREGATE_RECORD).getValue());
        tsChangedNotify = Boolean.valueOf(context.getProperty(TS_CHANGE_NOTIFY).getValue());
        minPublishInterval = context.getProperty(MIN_PUBLISH_INTERVAL).asLong();

        subscriberUid = opcUaService.subscribe(tagNames, msgQueue, tsChangedNotify, minPublishInterval);

        recordAggregator = new RecordAggregator(tagNames, minPublishInterval);
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {

        if(!aggregateRecord) {
            String msg;
            while ((msg = msgQueue.poll()) != null) {
                // Write the results back out to a flow file
                FlowFile flowFile = session.create();

                byte[] outputMsgBytes = msg.getBytes();
                if (flowFile != null) {
                    try {
                        flowFile = session.write(flowFile, (OutputStream out) -> out.write(outputMsgBytes));

                        // Transfer data to flow file
                        session.transfer(flowFile, SUCCESS);
                    } catch (ProcessException ex) {
                        getLogger().error("Unable to process", ex);
                        session.transfer(flowFile, FAILURE);
                    }
                }
            }
        } else {
            String rawMsg;
            while ((rawMsg = msgQueue.poll()) != null) {
                recordAggregator.aggregate(rawMsg);
            }

            List<String> list = recordAggregator.getReadyRecords();
            for(String msg: list) {
                // Write the results back out to a flow file
                FlowFile flowFile = session.create();

                byte[] outputMsgBytes = msg.getBytes();
                if (flowFile != null) {
                    try {
                        flowFile = session.write(flowFile, (OutputStream out) -> out.write(outputMsgBytes));

                        // add header to attribute (remember to add time stamp colum to the first)
                        Map<String, String> attrMap = new HashMap<>();
                        attrMap.put("csvHeader", "timestamp," + String.join(",", tagNames) + System.getProperty("line.separator"));
                        flowFile = session.putAllAttributes(flowFile, attrMap);

                        // Transfer data to flow file
                        session.transfer(flowFile, SUCCESS);
                    } catch (ProcessException ex) {
                        getLogger().error("Unable to process", ex);
                        session.transfer(flowFile, FAILURE);
                    }
                }
            }
        }

    }

    @OnStopped
    public void onStopped(final ProcessContext context) {

        getLogger().debug("Unsubscribing from OPC Server...");
        opcUaService.unsubscribe(subscriberUid);

    }

    private List<String> parseFile(Path filePath) throws IOException {
        byte[] encoded;
        encoded = Files.readAllBytes(filePath);
        String fileContent = new String(encoded, Charset.defaultCharset());
        return new BufferedReader(new StringReader(fileContent)).lines().collect(Collectors.toList());
    }
}
