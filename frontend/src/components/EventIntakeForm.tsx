import { useEffect, useState } from "react";
import "./EventIntakeForm.css";
import { apiFetch } from "../services/apiFetch";

const DRAFT_STORAGE_KEY = "bloom-event-intake-draft";

interface FormData {
  // Part 1: Client & Event Information
  companyName: string;
  contactPerson: string;
  contactEmailPhone: string;
  targetEventDates: string;
  customerType: string;

  // Part 2: Core Theme & Objectives
  primaryGoals: string[];
  otherGoal: string;
  coreValuesThemes: string;
  companyCulture: string;
  cultureAttributes: string[];
  negativeAttributes: string;
  measureCulture: string;
  governmentInvolvement: string;

  // Part 3: Event Scope & Logistics
  expectedParticipants: string;
  eventDuration: string;
  energyPacing: string;
  ageGroup: string[];
  // budgetMin: string;
  // budgetMax: string;
  budgetOpen: boolean;
  currency: string;

  // Part 4: Venue & Facilities
  venueType: string;
  venueIndoorOptions: string[];
  venueOutdoorOptions: string[];
  hasTheatre: string;
  hasSoundSystem: string;
  facilityLocation: string;
  venueNotes: string;

  // Part 5: Cultural Considerations
  culturalDiversity: string[];
  primaryLanguages: string;
  dietaryRestrictions: string[];
  regionalDistribution: string;
  // Part 5
  culturalSensitivities: string; // Restored for textarea
  cultureSensitivities: string[]; // checkboxes

  // Part 6: Participant & Interaction Goals
  participantTypes: string[];
  interactionGoals: string[];
  otherInteractionGoal: string;
  audienceExperience: string;

  // Part 7: Activity Style & Tone
  activityFeel: string[];
  activitiesToAvoid: string;

  // Part 8: Food & Amenities
  cateringOptions: string[];
  lunchBreak: string;
  foodBooths: string;
  cateringDetails: string[];
  giveaways: string;
  giveawaysDetails: string;

  // Part 9: Additional Context
  additionalContext: string;
}

const initialFormData: FormData = {
  companyName: "",
  contactPerson: "",
  contactEmailPhone: "",
  targetEventDates: "",
  customerType: "",
  primaryGoals: [],
  otherGoal: "",
  coreValuesThemes: "",
  companyCulture: "",
  cultureAttributes: [],
  negativeAttributes: "",
  measureCulture: "",
  governmentInvolvement: "",
  expectedParticipants: "",
  eventDuration: "",
  energyPacing: "",
  ageGroup: [],
  // budgetMin: "",
  // budgetMax: "",
  budgetOpen: false,
  currency: "USD",
  venueType: "",
  venueIndoorOptions: [],
  venueOutdoorOptions: [],
  hasTheatre: "",
  hasSoundSystem: "",
  facilityLocation: "",
  venueNotes: "",
  culturalDiversity: [],
  primaryLanguages: "",
  dietaryRestrictions: [],
  regionalDistribution: "",
  culturalSensitivities: "",
  cultureSensitivities: [],
  participantTypes: [],
  interactionGoals: [],
  otherInteractionGoal: "",
  audienceExperience: "",
  activityFeel: [],
  activitiesToAvoid: "",
  cateringOptions: [],
  lunchBreak: "",
  foodBooths: "",
  giveaways: "",
  giveawaysDetails: "",
  cateringDetails: [],
  additionalContext: "",
};

/** Restore a saved draft from localStorage, falling back to a blank form. */
function loadDraft(): FormData {
  try {
    const saved = localStorage.getItem(DRAFT_STORAGE_KEY);
    if (!saved) return initialFormData;
    return { ...initialFormData, ...JSON.parse(saved) };
  } catch {
    return initialFormData;
  }
}

const cultureAttributeOptions = [
  "Competitive",
  "Fast-paced",
  "High Achievers",
  "Old School",
  "Male Dominant",
  "Siloed",
  "Collaborative",
  "Hierarchical",
];

const measureCultureOptions = [
  "Age / Seniority Level",
  "First-time Managers",
  "Diversity & Inclusion Metrics",
  "Employee Engagement Surveys",
  "Turnover Rate",
];

const cateringDetailOptions = [
  "Breakfast",
  "Morning Coffee/Tea",
  "Lunch",
  "Afternoon Snack",
  "Dinner",
  "Beverages only",
];

const customerTypeOptions = [
  "Corporate / Enterprise",
  "Startup / Tech Company",
  "Government / Public Sector",
  "Non-Profit Organization",
  "Educational Institution",
  "Healthcare / Medical",
  "Financial Services",
  "Retail / Consumer Goods",
  "Manufacturing / Industrial",
  "Retail / Consumer Goods",
  "Manufacturing / Industrial",
  "Oil and Gas / Energy",
  "Other",
];

const primaryGoalOptions = [
  "Re-energize and motivate the team",
  "Celebrate a recent success / milestone",
  "Integrate new team members / new hires",
  "Solve a specific business challenge",
  "Develop specific skills (e.g., leadership, communication)",
];

const durationOptions = [
  { value: "half-day", label: "Half-Day (approx. 3-4 hours)" },
  { value: "full-day", label: "Full-Day (approx. 6-8 hours, including breaks)" },
  { value: "multi-day", label: "Multi-Day Retreat" },
];

const energyOptions = [
  { value: "high-energy", label: "High-energy, fast-paced, and competitive" },
  { value: "relaxed", label: "Relaxed, mindful, and conversational" },
  { value: "balanced", label: "A balanced mix of high-energy activities and focused downtime" },
];

const ageGroupOptions = [
  "18-25",
  "26-35",
  "36-45",
  "46-55",
  "55+",
  "Mixed Age Groups",
];

const currencyOptions = ["USD", "EUR", "EGP"];

const venueTypeOptions = [
  { value: "indoor", label: "Indoor Venue" },
  { value: "outdoor", label: "Outdoor Venue" },
  { value: "hybrid", label: "Hybrid (Indoor + Outdoor)" },
];
0
const venueIndoorOptionsList = ["Nile", "Alex", "Pyramids"];

const venueOutdoorOptionsList = ["Backyard", "G Floor"];

const yesNoOptions = [
  { value: "yes", label: "Yes" },
  { value: "no", label: "No" },
  { value: "not-sure", label: "Not Sure / To Be Determined" },
];

const culturalDiversityOptions = [
  "Homogeneous (mostly similar cultural backgrounds)",
  "Moderately diverse",
  "Highly diverse (multiple nationalities/cultures)",
  "International team across different time zones",
  "Mix of local and expatriate employees",
];

const dietaryRestrictionOptions = [
  "Vegetarian options needed",
  "Vegan options needed",
  "Halal requirements",
  "Kosher requirements",
  "Gluten-free options needed",
  "Nut-free / allergy considerations",
  "No alcohol",
  "No specific restrictions",
];

const participantOptions = [
  "A single, intact team/department",
  "Cross-functional teams who don't often work together",
  "Senior leadership / executives",
  "Mostly new hires",
  "A mix of all levels and tenures",
];

const interactionGoalOptions = [
  "Fostering new connections (Breaking down silos)",
  "Strengthening existing team bonds",
  "Practicing hands-on collaboration and problem-solving",
  "Improving communication and trust",
  "Other",
];

const activityFeelOptions = [
  "Creative and imaginative (e.g., making things, brainstorming)",
  "Intellectually challenging (e.g., puzzles, strategy)",
  "Fun and lighthearted (e.g., games, laughter)",
  "Physical and active (e.g., sports, movement)",
  "Professional and focused on skill development",
  "Motivational",
  "Other",
];

const cateringOptions = [
  "Breakfast / Morning Refreshments",
  "Coffee Breaks",
  "Lunch",
  "Dinner",
  "Snacks / Light Bites",
  "Full Catering Service",
  "No Catering Required",
];

const lunchBreakOptions = [
  { value: "30min", label: "30 minutes" },
  { value: "45min", label: "45 minutes" },
  { value: "1hour", label: "1 hour" },
  { value: "1.5hours", label: "1.5 hours" },
  { value: "flexible", label: "Flexible / To Be Determined" },
];

/** A form-section wrapper whose body can be collapsed to keep the long form scannable. */
function CollapsibleSection({
  number,
  title,
  isOpen,
  onToggle,
  children,
}: {
  number: string;
  title: string;
  isOpen: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <section className={`form-section ${isOpen ? "is-open" : "is-collapsed"}`}>
      <h2>
        <button
          type="button"
          className="section-header"
          onClick={onToggle}
          aria-expanded={isOpen}
        >
          <span className="section-number">{number}</span>
          <span className="section-title-text">{title}</span>
          <svg
            className="section-chevron"
            xmlns="http://www.w3.org/2000/svg"
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        </button>
      </h2>
      {isOpen && <div className="section-body">{children}</div>}
    </section>
  );
}

export default function EventIntakeForm() {
  const [formData, setFormData] = useState<FormData>(loadDraft);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitStatus, setSubmitStatus] = useState<{
    type: "success" | "error" | null;
    message: string;
  }>({ type: null, message: "" });
  const [openSections, setOpenSections] = useState<Set<number>>(new Set([1]));

  const toggleSection = (n: number) => {
    setOpenSections((prev) => {
      const next = new Set(prev);
      if (next.has(n)) {
        next.delete(n);
      } else {
        next.add(n);
      }
      return next;
    });
  };

  // Autosave: persist every change so the form survives a tab switch, reload, or navigation away.
  useEffect(() => {
    try {
      localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(formData));
    } catch {
      // localStorage unavailable (private browsing, quota, etc.) — fail silently, autosave is best-effort.
    }
  }, [formData]);

  const handleInputChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleCheckboxChange = (field: keyof FormData, value: string) => {
    setFormData((prev) => {
      const currentValues = prev[field] as string[];
      if (currentValues.includes(value)) {
        return { ...prev, [field]: currentValues.filter((v) => v !== value) };
      } else {
        return { ...prev, [field]: [...currentValues, value] };
      }
    });
  };

  const handleRadioChange = (field: keyof FormData, value: string) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  const handleVenueTypeChange = (value: string) => {
    setFormData((prev) => ({
      ...prev,
      venueType: value,
      venueIndoorOptions: value === "indoor" || value === "hybrid" ? prev.venueIndoorOptions : [],
      venueOutdoorOptions: value === "outdoor" || value === "hybrid" ? prev.venueOutdoorOptions : [],
    }));
  };

  const handleBudgetOpenToggle = (checked: boolean) => {
    setFormData((prev) => ({
      ...prev,
      budgetOpen: checked,
      // budgetMin: checked ? "" : prev.budgetMin,
      // budgetMax: checked ? "" : prev.budgetMax,
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!formData.companyName.trim()) {
      setSubmitStatus({
        type: "error",
        message: "Company Name is required.",
      });
      return;
    }

    setIsSubmitting(true);
    setSubmitStatus({ type: null, message: "" });

    try {
      const response = await apiFetch("/api/v1/rag/event-intake", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(formData),
      });

      const result = await response.json();

      if (response.ok) {
        const viewLinkText = result.viewLink
          ? ` View it here: ${result.viewLink}`
          : "";
        setSubmitStatus({
          type: "success",
          message: `Brief submitted successfully! PDF "${result.fileName}" uploaded to Google Drive folder "${result.folderName}".${viewLinkText}`,
        });
        setFormData(initialFormData);
        try {
          localStorage.removeItem(DRAFT_STORAGE_KEY);
        } catch {
          // best-effort cleanup
        }
      } else {
        if (result.authUrl) {
          setSubmitStatus({
            type: "error",
            message: `${result.error} Click here to authenticate: `,
          });
          window.open(result.authUrl, "_blank");
        } else {
          setSubmitStatus({
            type: "error",
            message: result.error || "Failed to submit brief.",
          });
        }
      }
    } catch (error) {
      setSubmitStatus({
        type: "error",
        message: "Network error. Please try again.",
      });
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="event-intake-container">
      <div className="form-header">
        <div className="header-accent"></div>
        <h1>Customer Brief Intake Form</h1>
      </div>

      <form onSubmit={handleSubmit} className="event-intake-form">
        {/* Part 1: Client & Event Information */}
        <CollapsibleSection number="01" title="Client & Event Information" isOpen={openSections.has(1)} onToggle={() => toggleSection(1)}>

          <div className="form-grid">
            <div className="form-group">
              <label htmlFor="companyName">
                Company Name <span className="required">*</span>
              </label>
              <input
                type="text"
                id="companyName"
                name="companyName"
                value={formData.companyName}
                onChange={handleInputChange}
                placeholder="Enter company name"
                required
              />
            </div>

            <div className="form-group">
              <label htmlFor="contactPerson">Contact Person</label>
              <input
                type="text"
                id="contactPerson"
                name="contactPerson"
                value={formData.contactPerson}
                onChange={handleInputChange}
                placeholder="Enter contact person name"
              />
            </div>

            <div className="form-group">
              <label htmlFor="contactEmailPhone">Contact Email / Phone</label>
              <input
                type="text"
                id="contactEmailPhone"
                name="contactEmailPhone"
                value={formData.contactEmailPhone}
                onChange={handleInputChange}
                placeholder="Enter email or phone number"
              />
            </div>

            <div className="form-group">
              <label htmlFor="targetEventDates">Target Event Date(s)</label>
              <input
                type="date"
                id="targetEventDates"
                name="targetEventDates"
                value={formData.targetEventDates}
                onChange={handleInputChange}
                required
              />
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="customerType">Type of Customer / Industry</label>
            <select
              id="customerType"
              name="customerType"
              value={formData.customerType}
              onChange={handleInputChange}
            >
              <option value="">Select customer type...</option>
              {customerTypeOptions.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </div>
        </CollapsibleSection>

        {/* Part 2: Core Theme & Objectives */}
        <CollapsibleSection number="02" title="Core Theme & Objectives" isOpen={openSections.has(2)} onToggle={() => toggleSection(2)}>

          <div className="form-group">
            <label>
              1. What is the primary, high-level goal for this event?
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              {primaryGoalOptions.map((option) => (
                <label key={option} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.primaryGoals.includes(option)}
                    onChange={() => handleCheckboxChange("primaryGoals", option)}
                  />
                  <span className="checkbox-custom"></span>
                  {option}
                </label>
              ))}
              <div className="other-option">
                <label className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.otherGoal !== ""}
                    onChange={() => {
                      if (formData.otherGoal) {
                        setFormData((prev) => ({ ...prev, otherGoal: "" }));
                      }
                    }}
                  />
                  <span className="checkbox-custom"></span>
                  Other:
                </label>
                <input
                  type="text"
                  name="otherGoal"
                  value={formData.otherGoal}
                  onChange={handleInputChange}
                  placeholder="Please specify"
                  className="other-input"
                />
              </div>
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="coreValuesThemes">
              2. What core values or themes should be the central focus?
              <span className="hint">
                (e.g., "Innovation," "Trust," "Commit, Collaborate, Care")
              </span>
            </label>
            <textarea
              id="coreValuesThemes"
              name="coreValuesThemes"
              value={formData.coreValuesThemes}
              onChange={handleInputChange}
              placeholder="Enter your core values or themes..."
              rows={3}
            />
          </div>

          <div className="form-group">
            <label htmlFor="companyCulture">
              3. Describe your company culture
              <span className="hint">
                (How would you describe your organization's work environment, values, and team dynamics?)
              </span>
            </label>
            <textarea
              id="companyCulture"
              name="companyCulture"
              value={formData.companyCulture}
              onChange={handleInputChange}
              placeholder="e.g., Fast-paced and innovative, Collaborative and supportive, Formal and structured, Casual and creative..."
              rows={3}
            />
          </div>

          <div className="form-group">
            <label>
              4. Company Culture Attributes
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              {cultureAttributeOptions.map((option) => (
                <label key={option} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.cultureAttributes.includes(option)}
                    onChange={() => handleCheckboxChange("cultureAttributes", option)}
                  />
                  <span className="checkbox-custom"></span>
                  {option}
                </label>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="negativeAttributes">
              5. Negative Cultural Attributes
              <span className="hint">(e.g., Siloed, Low Trust, Burnout - be honest!)</span>
            </label>
            <textarea
              id="negativeAttributes"
              name="negativeAttributes"
              value={formData.negativeAttributes}
              onChange={handleInputChange}
              rows={2}
            />
          </div>

          <div className="form-group">
            <label htmlFor="measureCulture">
              6. How do we measure culture?
              <span className="hint">(e.g., Age seniority, First-time managers)</span>
            </label>
            <select
              id="measureCulture"
              name="measureCulture"
              value={formData.measureCulture}
              onChange={handleInputChange}
            >
              <option value="">Select measurement...</option>
              {measureCultureOptions.map((option) => (
                <option key={option} value={option}>{option}</option>
              ))}
            </select>
          </div>

          <div className="form-group">
            <label>7. Government Involvement?</label>
            <div className="radio-group horizontal">
              <label className="radio-label compact">
                <input
                  type="radio"
                  name="governmentInvolvement"
                  value="yes"
                  checked={formData.governmentInvolvement === "yes"}
                  onChange={() => handleRadioChange("governmentInvolvement", "yes")}
                />
                <span className="radio-custom"></span>
                Yes
              </label>
              <label className="radio-label compact">
                <input
                  type="radio"
                  name="governmentInvolvement"
                  value="no"
                  checked={formData.governmentInvolvement === "no"}
                  onChange={() => handleRadioChange("governmentInvolvement", "no")}
                />
                <span className="radio-custom"></span>
                No
              </label>
            </div>
          </div>
        </CollapsibleSection>

        {/* Part 3: Event Scope & Logistics */}
        <CollapsibleSection number="03" title="Event Scope & Logistics" isOpen={openSections.has(3)} onToggle={() => toggleSection(3)}>

          <div className="form-group">
            <label htmlFor="expectedParticipants">
              4. Expected Number of Participants
            </label>
            <input
              type="text"
              id="expectedParticipants"
              name="expectedParticipants"
              value={formData.expectedParticipants}
              onChange={handleInputChange}
              placeholder="e.g., 25-30 people"
            />
          </div>

          <div className="form-group">
            <label>
              Budget Range <span className="required">*</span>
              <span className="hint"></span>
            </label>
            <div className="budget-row">
              <input
                type="number"
                name="budgetMin"
                // value={formData.budgetMin}
                onChange={handleInputChange}
                step={1000}
                disabled={formData.budgetOpen}
              />
              <span className="budget-sep">to</span>
              <input
                type="number"
                name="budgetMax"
                // value={formData.budgetMax}
                onChange={handleInputChange}
                // min={50000}
                // max={2000000}
                step={1000}
                disabled={formData.budgetOpen}
              />
              <select
                className="currency-select"
                name="currency"
                value={formData.currency}
                onChange={handleInputChange}
              >
                {currencyOptions.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </div>
            <label className="budget-open-toggle">
              <input
                type="checkbox"
                checked={formData.budgetOpen}
                onChange={(e) => handleBudgetOpenToggle(e.target.checked)}
              />
              Budget is open / flexible
            </label>
          </div>

          <div className="form-group">
            <label>
              5. Age Group of Participants
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              {ageGroupOptions.map((option) => (
                <label key={option} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.ageGroup.includes(option)}
                    onChange={() => handleCheckboxChange("ageGroup", option)}
                  />
                  <span className="checkbox-custom"></span>
                  {option}
                </label>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label>6. Desired Event Duration</label>
            <div className="radio-group">
              {durationOptions.map((option) => (
                <label key={option.value} className="radio-label">
                  <input
                    type="radio"
                    name="eventDuration"
                    value={option.value}
                    checked={formData.eventDuration === option.value}
                    onChange={() => handleRadioChange("eventDuration", option.value)}
                  />
                  <span className="radio-custom"></span>
                  {option.label}
                </label>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label>7. Desired Energy & Pacing</label>
            <div className="radio-group">
              {energyOptions.map((option) => (
                <label key={option.value} className="radio-label">
                  <input
                    type="radio"
                    name="energyPacing"
                    value={option.value}
                    checked={formData.energyPacing === option.value}
                    onChange={() => handleRadioChange("energyPacing", option.value)}
                  />
                  <span className="radio-custom"></span>
                  {option.label}
                </label>
              ))}
            </div>
          </div>
        </CollapsibleSection>

        {/* Part 4: Venue & Facilities */}
        <CollapsibleSection number="04" title="Venue & Facilities" isOpen={openSections.has(4)} onToggle={() => toggleSection(4)}>

          <div className="form-group">
            <label>8. Venue Type</label>
            <div className="radio-group">
              {venueTypeOptions.map((option) => (
                <label key={option.value} className="radio-label">
                  <input
                    type="radio"
                    name="venueType"
                    value={option.value}
                    checked={formData.venueType === option.value}
                    onChange={() => handleVenueTypeChange(option.value)}
                  />
                  <span className="radio-custom"></span>
                  {option.label}
                </label>
              ))}
            </div>
          </div>

          {(formData.venueType === "indoor" ||
            formData.venueType === "outdoor" ||
            formData.venueType === "hybrid") && (
            <div className="form-group">
              <label>
                Venue Options
                <span className="hint">(Check all that apply)</span>
              </label>

              {(formData.venueType === "indoor" || formData.venueType === "hybrid") && (
                <div className="venue-subgroup">
                  <h3>Indoor</h3>
                  <div className="checkbox-group">
                    {venueIndoorOptionsList.map((option) => (
                      <label key={option} className="checkbox-label">
                        <input
                          type="checkbox"
                          checked={formData.venueIndoorOptions.includes(option)}
                          onChange={() => handleCheckboxChange("venueIndoorOptions", option)}
                        />
                        <span className="checkbox-custom"></span>
                        {option}
                      </label>
                    ))}
                  </div>
                </div>
              )}

              {(formData.venueType === "outdoor" || formData.venueType === "hybrid") && (
                <div className="venue-subgroup">
                  <h3>Outdoor</h3>
                  <div className="checkbox-group">
                    {venueOutdoorOptionsList.map((option) => (
                      <label key={option} className="checkbox-label">
                        <input
                          type="checkbox"
                          checked={formData.venueOutdoorOptions.includes(option)}
                          onChange={() => handleCheckboxChange("venueOutdoorOptions", option)}
                        />
                        <span className="checkbox-custom"></span>
                        {option}
                      </label>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}

          <div className="form-grid">
            <div className="form-group">
              <label>9. Is there a Theatre / Stage available?</label>
              <div className="radio-group horizontal">
                {yesNoOptions.map((option) => (
                  <label key={option.value} className="radio-label compact">
                    <input
                      type="radio"
                      name="hasTheatre"
                      value={option.value}
                      checked={formData.hasTheatre === option.value}
                      onChange={() => handleRadioChange("hasTheatre", option.value)}
                    />
                    <span className="radio-custom"></span>
                    {option.label}
                  </label>
                ))}
              </div>
            </div>

            <div className="form-group">
              <label>10. Is there a Sound System available?</label>
              <div className="radio-group horizontal">
                {yesNoOptions.map((option) => (
                  <label key={option.value} className="radio-label compact">
                    <input
                      type="radio"
                      name="hasSoundSystem"
                      value={option.value}
                      checked={formData.hasSoundSystem === option.value}
                      onChange={() => handleRadioChange("hasSoundSystem", option.value)}
                    />
                    <span className="radio-custom"></span>
                    {option.label}
                  </label>
                ))}
              </div>
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="venueNotes">
              Additional Venue Notes
              <span className="hint">(Any specific requirements or constraints)</span>
            </label>
            <textarea
              id="venueNotes"
              name="venueNotes"
              value={formData.venueNotes}
              onChange={handleInputChange}
              rows={2}
            />
          </div>

          <div className="form-group">
            <label htmlFor="facilityLocation">
              11b. Where would the facility be?
              <span className="hint">(Neighborhood, City, or Specific Venue if known)</span>
            </label>
            <input
              type="text"
              id="facilityLocation"
              name="facilityLocation"
              value={formData.facilityLocation}
              onChange={handleInputChange}
            />
          </div>
        </CollapsibleSection>

        {/* Part 5: Cultural Considerations */}
        <CollapsibleSection number="05" title="Cultural Considerations" isOpen={openSections.has(5)} onToggle={() => toggleSection(5)}>

          <div className="form-group">
            <label>
              11. Cultural Diversity of Participants
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              {culturalDiversityOptions.map((option) => (
                <label key={option} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.culturalDiversity.includes(option)}
                    onChange={() => handleCheckboxChange("culturalDiversity", option)}
                  />
                  <span className="checkbox-custom"></span>
                  {option}
                </label>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="primaryLanguages">
              12. Primary Languages Spoken
              <span className="hint">(What languages do participants primarily communicate in?)</span>
            </label>
            <input
              type="text"
              id="primaryLanguages"
              name="primaryLanguages"
              value={formData.primaryLanguages}
              onChange={handleInputChange}
              placeholder="e.g., English, Arabic, French..."
            />
          </div>

          <div className="form-group">
            <label>
              13. Dietary / Religious Restrictions
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              {dietaryRestrictionOptions.map((option) => (
                <label key={option} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.dietaryRestrictions.includes(option)}
                    onChange={() => handleCheckboxChange("dietaryRestrictions", option)}
                  />
                  <span className="checkbox-custom"></span>
                  {option}
                </label>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="regionalDistribution">
              14. Regional / Geographic Distribution
              <span className="hint">(Where are participants located?)</span>
            </label>
            <input
              type="text"
              id="regionalDistribution"
              name="regionalDistribution"
              value={formData.regionalDistribution}
              onChange={handleInputChange}
              placeholder="e.g., All in Cairo, Across Egypt, Multiple countries..."
            />
          </div>

          <div className="form-group">
            <label htmlFor="culturalSensitivities">
              15. Cultural Sensitivities to Be Aware Of
              <span className="hint">(Any topics, activities, or customs to respect)</span>
            </label>
            <textarea
              id="culturalSensitivities"
              name="culturalSensitivities"
              value={formData.culturalSensitivities} // Keeping as string for now to match backend
              onChange={handleInputChange}
              rows={3}
            />
          </div>

          <div className="form-group">
            <label>
              15. Cultural Sensitivities (Expanded)
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={formData.cultureSensitivities.includes("Conservative / Gender Separated")}
                  onChange={() => handleCheckboxChange("cultureSensitivities", "Conservative / Gender Separated")}
                />
                <span className="checkbox-custom"></span>
                Conservative / Gender Separated
              </label>
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={formData.cultureSensitivities.includes("No Music")}
                  onChange={() => handleCheckboxChange("cultureSensitivities", "No Music")}
                />
                <span className="checkbox-custom"></span>
                No Music
              </label>
            </div>
          </div>
        </CollapsibleSection>

        {/* Part 6: Participant & Interaction Goals */}
        <CollapsibleSection number="06" title="Participant & Interaction Goals" isOpen={openSections.has(6)} onToggle={() => toggleSection(6)}>

          <div className="form-group">
            <label>
              16. Who are the participants?
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              {participantOptions.map((option) => (
                <label key={option} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.participantTypes.includes(option)}
                    onChange={() =>
                      handleCheckboxChange("participantTypes", option)
                    }
                  />
                  <span className="checkbox-custom"></span>
                  {option}
                </label>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label>
              17. What is the primary interaction goal?
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              {interactionGoalOptions.map((option) => (
                <label key={option} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.interactionGoals.includes(option)}
                    onChange={() =>
                      handleCheckboxChange("interactionGoals", option)
                    }
                  />
                  <span className="checkbox-custom"></span>
                  {option}
                </label>
              ))}
              {formData.interactionGoals.includes("Other") && (
                <div className="nested-input" style={{ marginTop: "0.5rem" }}>
                  <input
                    type="text"
                    name="otherInteractionGoal"
                    value={formData.otherInteractionGoal}
                    onChange={handleInputChange}
                    placeholder="Please specify your other interaction goal"
                  />
                </div>
              )}
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="audienceExperience">
              18. What is the best experience for your audience?
              <span className="hint">
                (Describe the ideal takeaway or feeling you want participants to have)
              </span>
            </label>
            <textarea
              id="audienceExperience"
              name="audienceExperience"
              value={formData.audienceExperience}
              onChange={handleInputChange}
              placeholder="e.g., Leave feeling energized and connected, Have practical tools they can apply immediately, Feel appreciated and valued as team members..."
              rows={3}
            />
          </div>
        </CollapsibleSection>

        {/* Part 7: Activity Style & Tone */}
        <CollapsibleSection number="07" title="Activity Style & Tone" isOpen={openSections.has(7)} onToggle={() => toggleSection(7)}>

          <div className="form-group">
            <label>
              19. What is the ideal "feel" for the activities?
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              {activityFeelOptions.map((option) => (
                <label key={option} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.activityFeel.includes(option)}
                    onChange={() => handleCheckboxChange("activityFeel", option)}
                  />
                  <span className="checkbox-custom"></span>
                  {option}
                </label>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label htmlFor="activitiesToAvoid">
              20. Are there any activities, styles, or "clichés" you want to AVOID?
              <span className="hint">
                Examples: "No trust falls", "Nothing too physical", "No role-playing or acting",
                "Avoid icebreakers", "No competitive games", "Nothing that singles people out",
                "No alcohol-related activities", "Avoid outdoor activities in heat"
              </span>
            </label>
            <textarea
              id="activitiesToAvoid"
              name="activitiesToAvoid"
              value={formData.activitiesToAvoid}
              onChange={handleInputChange}
              placeholder="Describe what you'd like to avoid..."
              rows={3}
            />
          </div>
        </CollapsibleSection>

        {/* Part 8: Food & Amenities */}
        <CollapsibleSection number="08" title="Food & Amenities" isOpen={openSections.has(8)} onToggle={() => toggleSection(8)}>

          <div className="form-group">
            <label>
              21. Catering Requirements
              <span className="hint">(Check all that apply)</span>
            </label>
            <div className="checkbox-group">
              {cateringOptions.map((option) => (
                <label key={option} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={formData.cateringDetails.includes(option)}
                    onChange={() => handleCheckboxChange("cateringDetails", option)}
                  />
                  <span className="checkbox-custom"></span>
                  {option}
                </label>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label>22. Giveaways / Swag</label>
            <div className="radio-group horizontal">
              {yesNoOptions.map((option) => (
                <label key={option.value} className="radio-label compact">
                  <input
                    type="radio"
                    name="giveaways"
                    value={option.value}
                    checked={formData.giveaways === option.value}
                    onChange={() => handleRadioChange("giveaways", option.value)}
                  />
                  <span className="radio-custom"></span>
                  {option.label}
                </label>
              ))}
            </div>
            {formData.giveaways === "yes" && (
              <div className="nested-input">
                <input
                  type="text"
                  name="giveawaysDetails"
                  value={formData.giveawaysDetails}
                  onChange={handleInputChange}
                  placeholder="Describe items (t-shirts, mugs, tech, etc.)"
                />
              </div>
            )}
          </div>

          <div className="form-group">
            <label>22. Lunch Break Duration</label>
            <div className="radio-group">
              {lunchBreakOptions.map((option) => (
                <label key={option.value} className="radio-label">
                  <input
                    type="radio"
                    name="lunchBreak"
                    value={option.value}
                    checked={formData.lunchBreak === option.value}
                    onChange={() => handleRadioChange("lunchBreak", option.value)}
                  />
                  <span className="radio-custom"></span>
                  {option.label}
                </label>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label>23. Do you need Food Booths / Stations?</label>
            <div className="radio-group horizontal">
              {yesNoOptions.map((option) => (
                <label key={option.value} className="radio-label compact">
                  <input
                    type="radio"
                    name="foodBooths"
                    value={option.value}
                    checked={formData.foodBooths === option.value}
                    onChange={() => handleRadioChange("foodBooths", option.value)}
                  />
                  <span className="radio-custom"></span>
                  {option.label}
                </label>
              ))}
            </div>
          </div>


        </CollapsibleSection>

        {/* Part 9: Additional Context */}
        <CollapsibleSection number="09" title="Additional Context" isOpen={openSections.has(9)} onToggle={() => toggleSection(9)}>

          <div className="form-group">
            <label htmlFor="additionalContext">
              25. Is there any other critical context, budget, or constraint we
              should be aware of?
            </label>
            <textarea
              id="additionalContext"
              name="additionalContext"
              value={formData.additionalContext}
              onChange={handleInputChange}
              placeholder="Share any additional information that might help us design your perfect event..."
              rows={4}
            />
          </div>
        </CollapsibleSection>

        {/* Submit Status */}
        {submitStatus.type && (
          <div className={`submit-status ${submitStatus.type}`}>
            <span className="status-icon">{submitStatus.type === 'success' ? '✓' : '⚠'}</span>
            <span>{submitStatus.message}</span>
          </div>
        )}

        {/* Submit Button */}
        <button
          type="submit"
          className="submit-button"
          disabled={isSubmitting}
        >
          {isSubmitting ? (
            <>
              <span className="spinner"></span>
              Submitting...
            </>
          ) : (
            <>
              <span className="button-icon">
                <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="square" strokeLinejoin="miter">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                  <polyline points="17 8 12 3 7 8"></polyline>
                  <line x1="12" y1="3" x2="12" y2="15"></line>
                </svg>
              </span>
              Submit Customer Brief
            </>
          )}
        </button>
      </form>
    </div>
  );
}
