package com.aicode.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LineDiff] 行级 LCS 差异计算与统一文本渲染。
 *
 * 两个实现细节决定断言形态：
 * 1. LCS 回溯时删除分支优先（dp[i+1][j] >= dp[i][j+1] 取 REMOVE）；
 * 2. split("\n") 让空文本 / 末尾换行各产生一个空行元素，diff 结果按此实际行为断言。
 */
class LineDiffTest {

    private fun ctx(text: String) = LineDiff.DiffLine(LineDiff.LineType.CONTEXT, text)
    private fun add(text: String) = LineDiff.DiffLine(LineDiff.LineType.ADD, text)
    private fun del(text: String) = LineDiff.DiffLine(LineDiff.LineType.REMOVE, text)

    @Test
    fun identical_text_produces_only_context_lines() {
        assertEquals(
            listOf(ctx("a"), ctx("b"), ctx("c")),
            LineDiff.diff("a\nb\nc", "a\nb\nc")
        )
    }

    @Test
    fun single_line_identical_is_context() {
        assertEquals(listOf(ctx("hello")), LineDiff.diff("hello", "hello"))
    }

    @Test
    fun pure_insertion_produces_add_lines() {
        // 空文本 split 出空行，先删空行再逐行新增
        assertEquals(
            listOf(del(""), add("a"), add("b")),
            LineDiff.diff("", "a\nb")
        )
    }

    @Test
    fun insert_into_middle_produces_add() {
        assertEquals(
            listOf(ctx("a"), add("b"), ctx("c")),
            LineDiff.diff("a\nc", "a\nb\nc")
        )
    }

    @Test
    fun pure_deletion_produces_remove_lines() {
        assertEquals(
            listOf(del("a"), del("b"), add("")),
            LineDiff.diff("a\nb", "")
        )
    }

    @Test
    fun delete_from_middle_produces_remove() {
        assertEquals(
            listOf(ctx("a"), del("b"), ctx("c")),
            LineDiff.diff("a\nb\nc", "a\nc")
        )
    }

    @Test
    fun modified_middle_line_produces_remove_then_add() {
        assertEquals(
            listOf(ctx("a"), del("b"), add("x"), ctx("c")),
            LineDiff.diff("a\nb\nc", "a\nx\nc")
        )
    }

    @Test
    fun adjacent_duplicate_lines_favor_removal() {
        // 删除分支优先：a 被删后 b 仍是公共行
        assertEquals(
            listOf(del("a"), ctx("b")),
            LineDiff.diff("a\nb", "b")
        )
    }

    @Test
    fun both_empty_texts_produce_single_empty_context() {
        assertEquals(listOf(ctx("")), LineDiff.diff("", ""))
    }

    @Test
    fun blank_line_in_middle_is_context() {
        assertEquals(
            listOf(ctx("a"), ctx(""), ctx("b")),
            LineDiff.diff("a\n\nb", "a\n\nb")
        )
    }

    @Test
    fun multi_line_mixed_change() {
        assertEquals(
            listOf(ctx("l1"), del("l2"), del("l3"), add("l2x"), ctx("l4"), add("l5")),
            LineDiff.diff("l1\nl2\nl3\nl4", "l1\nl2x\nl4\nl5")
        )
    }

    @Test
    fun trailing_newline_addition_appears_as_empty_add() {
        assertEquals(
            listOf(ctx("a"), add("")),
            LineDiff.diff("a", "a\n")
        )
    }

    @Test
    fun trailing_newline_removal_appears_as_empty_remove() {
        assertEquals(
            listOf(ctx("a"), del("")),
            LineDiff.diff("a\n", "a")
        )
    }

    @Test
    fun to_unified_uses_prefix_per_line_type() {
        assertEquals(" a\n-b\n+x\n c", LineDiff.toUnified("a\nb\nc", "a\nx\nc"))
    }

    @Test
    fun to_unified_identical_text_uses_space_prefix() {
        assertEquals(" hello", LineDiff.toUnified("hello", "hello"))
    }

    @Test
    fun to_unified_empty_to_multi_line() {
        // 空文本 → 多行：先删空行（-），再逐行新增
        assertEquals("-\n+a\n+b", LineDiff.toUnified("", "a\nb"))
    }

    @Test
    fun to_unified_trailing_newline() {
        assertEquals(" a\n+", LineDiff.toUnified("a", "a\n"))
    }

    @Test
    fun to_unified_lines_starting_with_plus_minus_are_not_ambiguous() {
        // 标记位只取首列：内容以 +/- 开头的行渲染后仍可还原原文
        assertEquals(" -x\n +y", LineDiff.toUnified("-x\n+y", "-x\n+y"))
    }
}