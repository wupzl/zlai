package com.harmony.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "app.jwt.access-secret=test-access-secret",
        "app.jwt.refresh-secret=test-refresh-secret"
})
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
