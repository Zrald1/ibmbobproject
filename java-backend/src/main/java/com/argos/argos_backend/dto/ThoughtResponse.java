package com.argos.argos_backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ThoughtResponse(
    String thought,
    String expression,
    @JsonProperty("hand_gesture")
    String handGesture,
    String model
) {}
