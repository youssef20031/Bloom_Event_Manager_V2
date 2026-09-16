package com.bloom.eventmanager.proposal.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventIntakeData(
        String companyName, String contactPerson, String contactEmailPhone, String targetEventDates, String customerType,
        List<String> primaryGoals, String otherGoal, String coreValuesThemes, String companyCulture,
        List<String> cultureAttributes, String negativeAttributes, String measureCulture, String governmentInvolvement,
        String expectedParticipants, String eventDuration, String energyPacing, List<String> ageGroup,
        String venueType, String hasTheatre, String hasSoundSystem, String facilityLocation, String venueNotes,
        List<String> culturalDiversity, String primaryLanguages, List<String> dietaryRestrictions, String regionalDistribution,
        String culturalSensitivities, List<String> cultureSensitivities,
        List<String> participantTypes, List<String> interactionGoals, String otherInteractionGoal, String audienceExperience,
        List<String> activityFeel, String activitiesToAvoid,
        List<String> cateringOptions, String lunchBreak, String foodBooths, List<String> cateringDetails,
        String giveaways, String giveawaysDetails, String additionalContext) {
}
