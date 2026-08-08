package com.llm.gateway.admin.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageBoundsTest {

    @Test
    void rejectsInvalidPageAndSize() {
        assertThatThrownBy(() -> PageBounds.of(0, 20)).hasMessage("page 必须大于等于 1");
        assertThatThrownBy(() -> PageBounds.of(1, 0)).hasMessage("size 必须在 1 到 100 之间");
        assertThatThrownBy(() -> PageBounds.of(1, 101)).hasMessage("size 必须在 1 到 100 之间");
    }
}
