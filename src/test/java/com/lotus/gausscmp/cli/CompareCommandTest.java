package com.lotus.gausscmp.cli;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CompareCommandTest {

    @Test
    void missingConfigFileExitsWithError() {
        int exitCode = new Main().execute("compare", "-c", "nonexistent.yaml");
        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    void versionFlagPrintsVersion() {
        int exitCode = new Main().execute("--version");
        assertThat(exitCode).isEqualTo(0);
    }
}
