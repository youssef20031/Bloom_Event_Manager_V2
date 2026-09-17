package com.bloom.eventmanager.pdf;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptRunsTest {

    @Test
    void splitsBoldMarkdownIntoRuns() {
        List<ScriptRuns.Run> runs = ScriptRuns.split("Theme **Concept**: a party");

        assertThat(runs).containsExactly(
                new ScriptRuns.Run("Theme ", false, false),
                new ScriptRuns.Run("Concept", false, true),
                new ScriptRuns.Run(": a party", false, false));
    }

    @Test
    void switchesRunsWhenScriptChangesAndNeutralsStickToTheCurrentRun() {
        List<ScriptRuns.Run> runs = ScriptRuns.split("Hello, مرحبا 123 world");

        assertThat(runs).containsExactly(
                new ScriptRuns.Run("Hello, ", false, false),
                new ScriptRuns.Run("مرحبا 123 ", true, false),
                new ScriptRuns.Run("world", false, false));
    }

    @Test
    void boldSurvivesScriptSwitching() {
        List<ScriptRuns.Run> runs = ScriptRuns.split("**Bloom بلوم**");

        assertThat(runs).containsExactly(
                new ScriptRuns.Run("Bloom ", false, true),
                new ScriptRuns.Run("بلوم", true, true));
    }

    @Test
    void anAllArabicLineIsOneRun() {
        assertThat(ScriptRuns.split("فعاليات بناء الفريق")).singleElement()
                .satisfies(run -> assertThat(run.arabic()).isTrue());
    }

    @Test
    void emptyLineYieldsNoRuns() {
        assertThat(ScriptRuns.split("")).isEmpty();
    }
}
