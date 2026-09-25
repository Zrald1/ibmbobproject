package com.argos.argos_backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Unified chat reply.
 *
 * <p>Carries BOTH {@code response} (Python/primary Android contract) and
 * {@code reply} (Java-fallback Android contract) with the same text so every
 * client version parses it cleanly, plus the parsed expression/gesture, the
 * serving model, and an optional structured action.</p>
 */
public class ChatResponse {

    private boolean success;
    private String source;
    private String response;
    private String reply;
    private String expression;

    @JsonProperty("hand_gesture")
    private String handGesture;

    private String model;
    private ActionDto action;
    private boolean retrySuggested;

    public ChatResponse() {
    }

    /**
     * Build a successful reply. {@code text} populates both {@code response} and
     * {@code reply}.
     */
    public static ChatResponse ok(String text, String expression, String handGesture,
                                  String model, ActionDto action, String source, boolean retrySuggested) {
        ChatResponse r = new ChatResponse();
        r.success = true;
        r.source = source;
        r.response = text;
        r.reply = text;
        r.expression = expression;
        r.handGesture = handGesture;
        r.model = model;
        r.action = action;
        r.retrySuggested = retrySuggested;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getHandGesture() {
        return handGesture;
    }

    public void setHandGesture(String handGesture) {
        this.handGesture = handGesture;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public ActionDto getAction() {
        return action;
    }

    public void setAction(ActionDto action) {
        this.action = action;
    }

    public boolean isRetrySuggested() {
        return retrySuggested;
    }

    public void setRetrySuggested(boolean retrySuggested) {
        this.retrySuggested = retrySuggested;
    }
}
