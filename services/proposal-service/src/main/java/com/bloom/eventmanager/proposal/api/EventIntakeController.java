package com.bloom.eventmanager.proposal.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.proposal.domain.EventIntakeData;
import com.bloom.eventmanager.proposal.domain.IntakeSubmission;
import com.bloom.eventmanager.proposal.domain.SubmitIntakeUseCase;

@RestController
public class EventIntakeController {

    private final SubmitIntakeUseCase useCase;

    public EventIntakeController(SubmitIntakeUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v1/event-intake")
    public IntakeSubmission submit(@RequestBody EventIntakeData data) {
        return useCase.execute(data);
    }
}
