package com.aws.cfn;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.aws.cfn.exceptions.TerminalException;
import com.aws.cfn.metrics.MetricsPublisher;
import com.aws.cfn.proxy.CallbackAdapter;
import com.aws.cfn.proxy.HandlerRequest;
import com.aws.cfn.proxy.OperationStatus;
import com.aws.cfn.proxy.ProgressEvent;
import com.aws.cfn.proxy.ResourceHandlerRequest;
import com.aws.cfn.resource.SchemaValidator;
import com.aws.cfn.resource.Serializer;
import com.aws.cfn.resource.exceptions.ValidationException;
import com.aws.cfn.scheduler.CloudWatchScheduler;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LambdaWrapperTest {

    private static final String TEST_DATA_BASE_PATH = "src/test/java/com/aws/cfn/data/%s";

    private InputStream loadRequestStream(final String fileName) {
        final File file = new File(String.format(TEST_DATA_BASE_PATH, fileName));

        try {
            return new FileInputStream(file);
        } catch (final FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Context getLambdaContext() {
        final LambdaLogger lambdaLogger = mock(LambdaLogger.class);

        final Context context = mock(Context.class);
        when(context.getInvokedFunctionArn()).thenReturn("arn:aws:lambda:aws-region:acct-id:function:testHandler:PROD");
        when(context.getLogger()).thenReturn(lambdaLogger);

        return context;
    }

    private void testInvokeHandler_NullResponse(final String requestDataPath,
                                                final Action action) throws IOException {
        final CallbackAdapter callbackAdapter = mock(CallbackAdapter.class);
        final MetricsPublisher metricsPublisher = mock(MetricsPublisher.class);
        final CloudWatchScheduler scheduler = mock(CloudWatchScheduler.class);
        final SchemaValidator validator = mock(SchemaValidator.class);
        final Serializer serializer = new Serializer();
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, metricsPublisher, scheduler, validator, serializer);
        final TestModel model = new TestModel();

        // a null response is a terminal fault
        wrapper.setInvokeHandlerResponse(null);

        final ResourceHandlerRequest<TestModel> resourceHandlerRequest = mock(ResourceHandlerRequest.class);
        when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        final InputStream in = loadRequestStream(requestDataPath);
        final OutputStream out = new ByteArrayOutputStream();
        final Context context = getLambdaContext();

        wrapper.handleRequest(in, out, context);

        // validation failure metric should be published for final error handling
        verify(metricsPublisher, times(1)).publishExceptionMetric(
            any(Date.class), any(), any(TerminalException.class));

        // all metrics should be published even on terminal failure
        verify(metricsPublisher, times(1)).setResourceTypeName(
            "AWS::Test::TestModel");
        verify(metricsPublisher, times(1)).publishInvocationMetric(
            any(Date.class), eq(action));
        verify(metricsPublisher, times(1)).publishDurationMetric(
            any(Date.class), eq(action), anyLong());

        // verify that model validation occurred for CREATE/UPDATE/DELETE
        if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));
        }

        // no re-invocation via CloudWatch should occur
        verify(scheduler, times(0)).rescheduleAfterMinutes(
            anyString(), anyInt(), any(HandlerRequest.class));
        verify(scheduler, times(0)).cleanupCloudWatchEvents(
            any(), any());

        // verify output response
        assertThat(
            out.toString(),
            is(equalTo("{\"operationStatus\":\"FAILED\",\"resourceModel\":{\"property2\":123,\"property1\":\"abc\"}," +
                    "\"message\":\"Handler failed to provide a response.\"}"))
        );
    }

    @Test
    public void testInvokeHandler_Create_NullResponse() throws IOException {
        testInvokeHandler_NullResponse("create.request.json", Action.CREATE);
    }

    @Test
    public void testInvokeHandler_Read_NullResponse() throws IOException {
        testInvokeHandler_NullResponse("read.request.json", Action.READ);
    }

    @Test
    public void testInvokeHandler_Update_NullResponse() throws IOException {
        testInvokeHandler_NullResponse("update.request.json", Action.UPDATE);
    }

    @Test
    public void testInvokeHandler_Delete_NullResponse() throws IOException {
        testInvokeHandler_NullResponse("delete.request.json", Action.DELETE);
    }

    @Test
    public void testInvokeHandler_List_NullResponse() throws IOException {
        testInvokeHandler_NullResponse("list.request.json", Action.LIST);
    }

    private void testInvokeHandler_Failed(final String requestDataPath,
                                          final Action action) throws IOException {

        final CallbackAdapter callbackAdapter = mock(CallbackAdapter.class);
        final MetricsPublisher metricsPublisher = mock(MetricsPublisher.class);
        final CloudWatchScheduler scheduler = mock(CloudWatchScheduler.class);
        final SchemaValidator validator = mock(SchemaValidator.class);
        final Serializer serializer = new Serializer();
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, metricsPublisher, scheduler, validator, serializer);
        final TestModel model = new TestModel();

        // explicit fault response is treated as an unsuccessful synchronous completion
        final ProgressEvent pe = new ProgressEvent();
        pe.setMessage("Custom Fault");
        pe.setStatus(OperationStatus.FAILED);
        wrapper.setInvokeHandlerResponse(pe);

        final ResourceHandlerRequest<TestModel> resourceHandlerRequest = mock(ResourceHandlerRequest.class);
        when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        final InputStream in = loadRequestStream(requestDataPath);
        final OutputStream out = new ByteArrayOutputStream();
        final Context context = getLambdaContext();

        wrapper.handleRequest(in, out, context);

        // all metrics should be published, once for a single invocation
        verify(metricsPublisher, times(1)).setResourceTypeName(
            "AWS::Test::TestModel");
        verify(metricsPublisher, times(1)).publishInvocationMetric(
            any(Date.class), eq(action));
        verify(metricsPublisher, times(1)).publishDurationMetric(
            any(Date.class), eq(action), anyLong());

        // validation failure metric should not be published
        verify(metricsPublisher, times(0)).publishExceptionMetric(
            any(Date.class), any(), any(Exception.class));

        // verify that model validation occurred for CREATE/UPDATE/DELETE
        if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));
        }

        // no re-invocation via CloudWatch should occur
        verify(scheduler, times(0)).rescheduleAfterMinutes(
            anyString(), anyInt(), any(HandlerRequest.class));
        verify(scheduler, times(0)).cleanupCloudWatchEvents(
            any(), any());

        // verify output response
        assertThat(
            out.toString(),
            is(equalTo("{\"operationStatus\":\"FAILED\",\"message\":\"Custom Fault\"}"))
        );
    }

    @Test
    public void testInvokeHandler_Create_Failed() throws IOException {
        testInvokeHandler_Failed("create.request.json", Action.CREATE);
    }

    @Test
    public void testInvokeHandler_Read_Failed() throws IOException {
        testInvokeHandler_Failed("read.request.json", Action.READ);
    }

    @Test
    public void testInvokeHandler_Update_Failed() throws IOException {
        testInvokeHandler_Failed("update.request.json", Action.UPDATE);
    }

    @Test
    public void testInvokeHandler_Delete_Failed() throws IOException {
        testInvokeHandler_Failed("delete.request.json", Action.DELETE);
    }

    @Test
    public void testInvokeHandler_List_Failed() throws IOException {
        testInvokeHandler_Failed("list.request.json", Action.LIST);
    }

    private void testInvokeHandler_CompleteSynchronously(final String requestDataPath,
                                                        final Action action) throws IOException {

        final CallbackAdapter callbackAdapter = mock(CallbackAdapter.class);
        final MetricsPublisher metricsPublisher = mock(MetricsPublisher.class);
        final CloudWatchScheduler scheduler = mock(CloudWatchScheduler.class);
        final SchemaValidator validator = mock(SchemaValidator.class);
        final Serializer serializer = new Serializer();
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, metricsPublisher, scheduler, validator, serializer);
        final TestModel model = new TestModel();

        // if the handler responds Complete, this is treated as a successful synchronous completion
        final ProgressEvent pe = new ProgressEvent();
        pe.setStatus(OperationStatus.SUCCESS);
        wrapper.setInvokeHandlerResponse(pe);

        final ResourceHandlerRequest<TestModel> resourceHandlerRequest = mock(ResourceHandlerRequest.class);
        when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        final InputStream in = loadRequestStream(requestDataPath);
        final OutputStream out = new ByteArrayOutputStream();
        final Context context = getLambdaContext();

        wrapper.handleRequest(in, out, context);

        // all metrics should be published, once for a single invocation
        verify(metricsPublisher, times(1)).setResourceTypeName(
            "AWS::Test::TestModel");
        verify(metricsPublisher, times(1)).publishInvocationMetric(
            any(Date.class), eq(action));
        verify(metricsPublisher, times(1)).publishDurationMetric(
            any(Date.class), eq(action), anyLong());

        // validation failure metric should not be published
        verify(metricsPublisher, times(0)).publishExceptionMetric(
            any(Date.class), any(), any(Exception.class));

        // verify that model validation occurred for CREATE/UPDATE/DELETE
        if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));
        }

        // no re-invocation via CloudWatch should occur
        verify(scheduler, times(0)).rescheduleAfterMinutes(
            anyString(), anyInt(), any(HandlerRequest.class));
        verify(scheduler, times(0)).cleanupCloudWatchEvents(
            any(), any());

        // verify output response
        assertThat(
            out.toString(),
            is(equalTo("{\"operationStatus\":\"SUCCESS\"}"))
        );
    }

    @Test
    public void testInvokeHandler_Create_CompleteSynchronously() throws IOException {
        testInvokeHandler_CompleteSynchronously("create.request.json", Action.CREATE);
    }

    @Test
    public void testInvokeHandler_Read_CompleteSynchronously() throws IOException {
        testInvokeHandler_CompleteSynchronously("read.request.json", Action.READ);
    }

    @Test
    public void testInvokeHandler_Update_CompleteSynchronously() throws IOException {
        testInvokeHandler_CompleteSynchronously("update.request.json", Action.UPDATE);
    }

    @Test
    public void testInvokeHandler_Delete_CompleteSynchronously() throws IOException {
        testInvokeHandler_CompleteSynchronously("delete.request.json", Action.DELETE);
    }

    @Test
    public void testInvokeHandler_List_CompleteSynchronously() throws IOException {
        testInvokeHandler_CompleteSynchronously("list.request.json", Action.LIST);
    }

    private void testInvokeHandler_InProgress(final String requestDataPath,
                                              final Action action) throws IOException {

        final CallbackAdapter callbackAdapter = mock(CallbackAdapter.class);
        final MetricsPublisher metricsPublisher = mock(MetricsPublisher.class);
        final CloudWatchScheduler scheduler = mock(CloudWatchScheduler.class);
        final SchemaValidator validator = mock(SchemaValidator.class);
        final Serializer serializer = new Serializer();
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, metricsPublisher, scheduler, validator, serializer);
        final TestModel model = new TestModel();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent pe = new ProgressEvent();
        pe.setStatus(OperationStatus.IN_PROGRESS);
        pe.setResourceModel(model);
        wrapper.setInvokeHandlerResponse(pe);

        final ResourceHandlerRequest<TestModel> resourceHandlerRequest = mock(ResourceHandlerRequest.class);
        when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        final InputStream in = loadRequestStream(requestDataPath);
        final OutputStream out = new ByteArrayOutputStream();
        final Context context = getLambdaContext();

        wrapper.handleRequest(in, out, context);

        // all metrics should be published, once for a single invocation
        verify(metricsPublisher, times(1)).setResourceTypeName(
            "AWS::Test::TestModel");
        verify(metricsPublisher, times(1)).publishInvocationMetric(
            any(Date.class), eq(action));
        verify(metricsPublisher, times(1)).publishDurationMetric(
            any(Date.class), eq(action), anyLong());

        // validation failure metric should not be published
        verify(metricsPublisher, times(0)).publishExceptionMetric(
            any(Date.class), any(), any(Exception.class));

        // verify that model validation occurred for CREATE/UPDATE/DELETE
        if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));
        }

        // re-invocation via CloudWatch should occur
        verify(scheduler, times(1)).rescheduleAfterMinutes(
            anyString(), eq(0), any(HandlerRequest.class));

        // this was a first invocation, so no cleanup is required
        verify(scheduler, times(0)).cleanupCloudWatchEvents(
            any(), any());

        // CloudFormation should receive a callback invocation
        // TODO

        // verify output response
        assertThat(
            out.toString(),
            is(equalTo("{\"operationStatus\":\"IN_PROGRESS\",\"resourceModel\":{}}"))
        );
    }

    @Test
    public void testInvokeHandler_Create_InProgress() throws IOException {
        testInvokeHandler_InProgress("create.request.json", Action.CREATE);
    }

    @Test
    public void testInvokeHandler_Read_InProgress() throws IOException {
        testInvokeHandler_InProgress("read.request.json", Action.READ);
    }

    @Test
    public void testInvokeHandler_Update_InProgress() throws IOException {
        testInvokeHandler_InProgress("update.request.json", Action.UPDATE);
    }

    @Test
    public void testInvokeHandler_Delete_InProgress() throws IOException {
        testInvokeHandler_InProgress("delete.request.json", Action.DELETE);
    }

    @Test
    public void testInvokeHandler_List_InProgress() throws IOException {
        testInvokeHandler_InProgress("list.request.json", Action.LIST);
    }

    private void testReInvokeHandler_InProgress(final String requestDataPath,
                                                final Action action) throws IOException {

        final CallbackAdapter callbackAdapter = mock(CallbackAdapter.class);
        final MetricsPublisher metricsPublisher = mock(MetricsPublisher.class);
        final CloudWatchScheduler scheduler = mock(CloudWatchScheduler.class);
        final SchemaValidator validator = mock(SchemaValidator.class);
        final Serializer serializer = new Serializer();
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, metricsPublisher, scheduler, validator, serializer);
        final TestModel model = new TestModel();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent pe = new ProgressEvent();
        pe.setStatus(OperationStatus.IN_PROGRESS);
        pe.setResourceModel(model);
        wrapper.setInvokeHandlerResponse(pe);

        final ResourceHandlerRequest<TestModel> resourceHandlerRequest = mock(ResourceHandlerRequest.class);
        when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        final InputStream in = loadRequestStream(requestDataPath);
        final OutputStream out = new ByteArrayOutputStream();
        final Context context = getLambdaContext();

        wrapper.handleRequest(in, out, context);

        // all metrics should be published, once for a single invocation
        verify(metricsPublisher, times(1)).setResourceTypeName(
            "AWS::Test::TestModel");
        verify(metricsPublisher, times(1)).publishInvocationMetric(
            any(Date.class), eq(action));
        verify(metricsPublisher, times(1)).publishDurationMetric(
            any(Date.class), eq(action), anyLong());

        // validation failure metric should not be published
        verify(metricsPublisher, times(0)).publishExceptionMetric(
            any(Date.class), any(), any(Exception.class));

        // verify that model validation occurred for CREATE/UPDATE/DELETE
        if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));
        }

        // re-invocation via CloudWatch should occur
        verify(scheduler, times(1)).rescheduleAfterMinutes(
            anyString(), eq(0), any(HandlerRequest.class));

        // this was a re-invocation, so a cleanup is required
        verify(scheduler, times(1)).cleanupCloudWatchEvents(
            eq("reinvoke-handler-4754ac8a-623b-45fe-84bc-f5394118a8be"),
            eq("reinvoke-target-4754ac8a-623b-45fe-84bc-f5394118a8be")
        );

        // CloudFormation should receive a callback invocation
        // TODO

        // verify output response
        assertThat(
            out.toString(),
            is(equalTo("{\"operationStatus\":\"IN_PROGRESS\",\"resourceModel\":{}}"))
        );
    }

    @Test
    public void testReInvokeHandler_Create_InProgress() throws IOException {
        testReInvokeHandler_InProgress("create.with-request-context.request.json", Action.CREATE);
    }

    @Test
    public void testReInvokeHandler_Read_InProgress() throws IOException {
        // TODO: READ handlers must return synchronously so this is probably a fault
        //testReInvokeHandler_InProgress("read.with-request-context.request.json", Action.READ);
    }

    @Test
    public void testReInvokeHandler_Update_InProgress() throws IOException {
        testReInvokeHandler_InProgress("update.with-request-context.request.json", Action.UPDATE);
    }

    @Test
    public void testReInvokeHandler_Delete_InProgress() throws IOException {
        testReInvokeHandler_InProgress("delete.with-request-context.request.json", Action.DELETE);
    }

    @Test
    public void testReInvokeHandler_List_InProgress() throws IOException {
        // TODO: LIST handlers must return synchronously so this is probably a fault
        //testReInvokeHandler_InProgress("list.with-request-context.request.json", Action.LIST);
    }

    private void testInvokeHandler_SchemaValidationFailure(final String requestDataPath,
                                                           final Action action) throws IOException {

        final CallbackAdapter callbackAdapter = mock(CallbackAdapter.class);
        final MetricsPublisher metricsPublisher = mock(MetricsPublisher.class);
        final CloudWatchScheduler scheduler = mock(CloudWatchScheduler.class);
        final SchemaValidator validator = mock(SchemaValidator.class);
        final Serializer serializer = new Serializer();
        doThrow(ValidationException.class)
            .when(validator).validateObject(any(JSONObject.class), any(InputStream.class));
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, metricsPublisher, scheduler, validator, serializer);
        final TestModel model = new TestModel();

        final ResourceHandlerRequest<TestModel> resourceHandlerRequest = mock(ResourceHandlerRequest.class);
        when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        final InputStream in = loadRequestStream(requestDataPath);
        final OutputStream out = new ByteArrayOutputStream();
        final Context context = getLambdaContext();

        wrapper.handleRequest(in, out, context);

        // validation failure metric should be published but no others
        verify(metricsPublisher, times(1)).publishExceptionMetric(
            any(Date.class), eq(action), any(Exception.class));

        // all metrics should be published, even for a single invocation
        verify(metricsPublisher, times(1)).setResourceTypeName(
            "AWS::Test::TestModel");
        verify(metricsPublisher, times(1)).publishInvocationMetric(
            any(Date.class), eq(action));

        // duration metric only published when the provider handler is invoked
        verify(metricsPublisher, times(0)).publishDurationMetric(
            any(Date.class), eq(action), anyLong());

        // verify that model validation occurred for CREATE/UPDATE/DELETE
        if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));
        }

        // no re-invocation via CloudWatch should occur
        verify(scheduler, times(0)).rescheduleAfterMinutes(
            anyString(), anyInt(), any(HandlerRequest.class));
        verify(scheduler, times(0)).cleanupCloudWatchEvents(
            any(), any());

        // CloudFormation should receive a callback invocation
        // TODO

        // verify output response
        assertThat(
            out.toString(),
            is(equalTo( "{\"operationStatus\":\"FAILED\",\"resourceModel\":{\"property2\":123,\"property1\":\"abc\"}}"))
        );
    }

    @Test
    public void testInvokeHandler_Create_SchemaValidationFailure() throws IOException {
        testInvokeHandler_SchemaValidationFailure("create.request.json", Action.CREATE);
    }

    @Test
    public void testInvokeHandler_Read_SchemaValidationFailure() throws IOException {
        // TODO: READ handlers must return synchronously so this is probably a fault
        //testReInvokeHandler_SchemaValidationFailure("read.with-request-context.request.json", Action.READ);
    }

    @Test
    public void testInvokeHandler_Update_SchemaValidationFailure() throws IOException {
        testInvokeHandler_SchemaValidationFailure("update.request.json", Action.UPDATE);
    }

    @Test
    public void testInvokeHandler_Delete_SchemaValidationFailure() throws IOException {
        testInvokeHandler_SchemaValidationFailure("delete.request.json", Action.DELETE);
    }

    @Test
    public void testInvokeHandler_List_SchemaValidationFailure() throws IOException {
        // TODO: LIST handlers must return synchronously so this is probably a fault
        //testInvokeHandler_SchemaValidationFailure("list.with-request-context.request.json", Action.LIST);
    }

    @Test
    public void testInvokeHandler_WithMalformedRequest() throws IOException {
        final CallbackAdapter callbackAdapter = mock(CallbackAdapter.class);
        final MetricsPublisher metricsPublisher = mock(MetricsPublisher.class);
        final CloudWatchScheduler scheduler = mock(CloudWatchScheduler.class);
        final SchemaValidator validator = mock(SchemaValidator.class);
        final Serializer serializer = new Serializer();
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, metricsPublisher, scheduler, validator, serializer);
        final TestModel model = new TestModel();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent pe = new ProgressEvent();
        pe.setStatus(OperationStatus.SUCCESS);
        pe.setResourceModel(model);
        wrapper.setInvokeHandlerResponse(pe);

        final ResourceHandlerRequest<TestModel> resourceHandlerRequest = mock(ResourceHandlerRequest.class);
        when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);


        // our ObjectMapper implementation will ignore extraneous fields rather than fail them
        // this slightly loosens the coupling between caller (CloudFormation) and handlers.
        final InputStream in = loadRequestStream("malformed.request.json");
        final OutputStream out = new ByteArrayOutputStream();
        final Context context = getLambdaContext();

        wrapper.handleRequest(in, out, context);

        // verify output response
        assertThat(
            out.toString(),
            is(equalTo("{\"operationStatus\":\"SUCCESS\",\"resourceModel\":{}}"))
        );
    }
}

