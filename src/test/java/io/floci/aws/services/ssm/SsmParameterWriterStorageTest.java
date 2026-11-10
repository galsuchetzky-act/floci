package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ssm.model.Parameter;
import io.floci.aws.services.ssm.model.ParameterHistory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SsmParameterWriterStorageTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void savedHistoryAndItsCopyKeepTheVersionWriter() throws Exception {
        Parameter parameter = new Parameter("example", "value", "String");
        parameter.setLastModifiedUser("arn:aws:iam::111111111111:user/writer");
        ParameterHistory history = new ParameterHistory(parameter);
        ParameterHistory saved = mapper.readValue(mapper.writeValueAsString(history), ParameterHistory.class);
        assertEquals(parameter.getLastModifiedUser(), saved.getLastModifiedUser());
        assertEquals(parameter.getLastModifiedUser(), new ParameterHistory(saved).getLastModifiedUser());
    }

    @Test
    void legacyStoredMetadataAndHistoryKeepAnUnknownWriter() throws Exception {
        assertNull(mapper.readValue("{\"Name\":\"old\",\"Version\":1}", Parameter.class).getLastModifiedUser());
        assertNull(mapper.readValue("{\"Name\":\"old\",\"Version\":1}", ParameterHistory.class).getLastModifiedUser());
    }
}
