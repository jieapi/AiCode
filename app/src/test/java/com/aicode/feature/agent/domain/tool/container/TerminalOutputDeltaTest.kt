package com.aicode.feature.agent.domain.tool.container

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalOutputDeltaTest {

    @Test
    fun emptyCurrentAfterNonEmptyOutput_returnsEmptyDelta() {
        assertEquals("", outputDelta("previous output", ""))
    }

    @Test
    fun bothOutputsEmpty_returnsEmptyDelta() {
        assertEquals("", outputDelta("", ""))
    }

    @Test
    fun emptyPrevious_returnsCurrentOutput() {
        assertEquals("first output", outputDelta("", "first output"))
    }

    @Test
    fun unchangedOutput_returnsEmptyDelta() {
        assertEquals("", outputDelta("same output", "same output"))
    }

    @Test
    fun appendedOutput_returnsOnlyNewSuffix() {
        assertEquals("XYZ", outputDelta("abc", "abcXYZ"))
    }

    @Test
    fun overlappingOutput_returnsOnlyNewSuffix() {
        assertEquals("XYZ", outputDelta("start:abc", "abcXYZ"))
    }

    @Test
    fun multipleOverlaps_usesLongestMatch() {
        assertEquals("c", outputDelta("ababa", "abac"))
    }

    @Test
    fun noOverlap_returnsCurrentOutput() {
        assertEquals("XYZ", outputDelta("abc", "XYZ"))
    }
}
