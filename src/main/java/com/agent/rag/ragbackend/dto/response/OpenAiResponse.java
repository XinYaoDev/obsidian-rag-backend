package com.agent.rag.ragbackend.dto.response;

import com.agent.rag.ragbackend.dto.request.OpenAiRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.util.List;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiResponse {
    private List<Choice> choices;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private OpenAiRequest.Message message;
    }
}
