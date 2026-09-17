import { useState, useEffect, useCallback } from "react";
import "./ProposalGenerator.css";
import { apiFetch } from "../services/apiFetch";

// ─── Types ─────────────────────────────────────────────────────

interface ProposalSlide {
    slideNumber: number;
    type: string;
    title: string;
    subtitle?: string;
    content: any;
}

interface Company {
    id: string;
    name: string;
    briefCount: number;
    chatCount: number;
}

// ─── Component ─────────────────────────────────────────────────

export default function ProposalGenerator() {
    const [companies, setCompanies] = useState<Company[]>([]);
    const [loadingCompanies, setLoadingCompanies] = useState(true);
    const [selectedCompany, setSelectedCompany] = useState<string | null>(null);
    const [slides, setSlides] = useState<ProposalSlide[] | null>(null);
    const [currentSlide, setCurrentSlide] = useState(0);
    const [isGenerating, setIsGenerating] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const [saveResult, setSaveResult] = useState<{ fileName: string; viewLink: string } | null>(null);
    const [isDownloading, setIsDownloading] = useState(false);

    // ─── Load Companies on Mount ─────────────────────────────────

    useEffect(() => {
        fetchCompanies();
    }, []);

    const fetchCompanies = async () => {
        setLoadingCompanies(true);
        try {
            const response = await apiFetch("/api/v1/rag/proposals/companies");
            const data = await response.json();

            if (!response.ok) {
                throw new Error(data.error || "Failed to load companies");
            }

            setCompanies(data.companies || []);
        } catch (err: any) {
            setError(err.message);
        } finally {
            setLoadingCompanies(false);
        }
    };

    // ─── Generate Proposal ──────────────────────────────────────

    const handleGenerate = async (companyName: string) => {
        setSelectedCompany(companyName);
        setIsGenerating(true);
        setError(null);

        try {
            const response = await apiFetch("/api/v1/rag/generate-proposal", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ companyName }),
            });

            const result = await response.json();

            if (!response.ok) {
                throw new Error(result.error || "Failed to generate proposal");
            }

            setSlides(result.slides);
            setCurrentSlide(0);
        } catch (err: any) {
            setError(err.message);
        } finally {
            setIsGenerating(false);
        }
    };

    // ─── Save to Google Drive ─────────────────────────────────

    const handleSaveToDrive = async () => {
        if (!slides || !selectedCompany) return;
        setIsSaving(true);
        setSaveResult(null);
        try {
            const response = await apiFetch("/api/v1/rag/proposals/save", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ slides, companyName: selectedCompany }),
            });
            const result = await response.json();
            if (!response.ok) throw new Error(result.error || "Failed to save");
            setSaveResult({ fileName: result.fileName, viewLink: result.viewLink });
        } catch (err: any) {
            setError(err.message);
        } finally {
            setIsSaving(false);
        }
    };

    // ─── Download as PDF ──────────────────────────────────────

    const handleDownloadPdf = async () => {
        if (!slides || !selectedCompany) return;
        setIsDownloading(true);
        try {
            const response = await apiFetch("/api/v1/rag/proposals/download", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ slides, companyName: selectedCompany }),
            });
            if (!response.ok) throw new Error("Failed to download");
            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = `${selectedCompany}_Proposal.pdf`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            window.URL.revokeObjectURL(url);
        } catch (err: any) {
            setError(err.message);
        } finally {
            setIsDownloading(false);
        }
    };

    // ─── Keyboard Navigation ────────────────────────────────────

    const goNext = useCallback(() => {
        if (slides && currentSlide < slides.length - 1) {
            setCurrentSlide((prev) => prev + 1);
        }
    }, [slides, currentSlide]);

    const goPrev = useCallback(() => {
        if (currentSlide > 0) {
            setCurrentSlide((prev) => prev - 1);
        }
    }, [currentSlide]);

    useEffect(() => {
        if (!slides) return;

        const handleKeyDown = (e: KeyboardEvent) => {
            if (e.key === "ArrowRight" || e.key === " ") {
                e.preventDefault();
                goNext();
            } else if (e.key === "ArrowLeft") {
                e.preventDefault();
                goPrev();
            }
        };

        window.addEventListener("keydown", handleKeyDown);
        return () => window.removeEventListener("keydown", handleKeyDown);
    }, [slides, goNext, goPrev]);

    // ─── Slide Renderers ────────────────────────────────────────

    const renderSlide = (slide: ProposalSlide, index: number) => {
        const isActive = index === currentSlide;
        const isExit = index < currentSlide;

        const className = `slide slide-${slide.type} ${isActive ? "active" : ""} ${isExit ? "exit-left" : ""}`;

        switch (slide.type) {
            case "cover":
                return (
                    <div key={index} className={className}>
                        <div className="cover-accent-bar" />
                        <div className="cover-title">{slide.content.companyName}</div>
                        <div className="cover-subtitle">{slide.subtitle || "Event Proposal"}</div>
                        <div className="cover-tagline">{slide.content.tagline}</div>
                        <div className="cover-meta">
                            <span>{slide.content.eventDate}</span>
                            <span>•</span>
                            <span>{slide.content.preparedBy}</span>
                        </div>
                    </div>
                );

            case "summary":
                return (
                    <div key={index} className={className}>
                        <div className="slide-title">{slide.title}</div>
                        <span className="slide-title-accent" />
                        <div className="slide-overview">{slide.content.overview}</div>
                        <ul className="slide-highlights">
                            {slide.content.highlights?.map((h: string, i: number) => (
                                <li key={i}>{h}</li>
                            ))}
                        </ul>
                    </div>
                );

            case "concept":
                return (
                    <div key={index} className={className}>
                        <div className="slide-title">{slide.title}</div>
                        <span className="slide-title-accent" />
                        <div className="concept-theme-name">{slide.content.themeName}</div>
                        <div className="concept-description">{slide.content.description}</div>
                        <div className="concept-connection">{slide.content.connectionToValues}</div>
                    </div>
                );

            case "agenda":
                return (
                    <div key={index} className={className}>
                        <div className="slide-title">{slide.title}</div>
                        <span className="slide-title-accent" />
                        <div className="agenda-timeline">
                            {slide.content.items?.map((item: any, i: number) => (
                                <div key={i} className="agenda-item">
                                    <div className="agenda-time">{item.time}</div>
                                    <div>
                                        <div className="agenda-activity">{item.activity}</div>
                                        <div className="agenda-desc">{item.description}</div>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                );

            case "activities":
                return (
                    <div key={index} className={className}>
                        <div className="slide-title">{slide.title}</div>
                        <span className="slide-title-accent" />
                        <div className="activities-grid">
                            {slide.content.activities?.map((act: any, i: number) => (
                                <div key={i} className="activity-card">
                                    <div className="activity-card-name">{act.name}</div>
                                    <div className="activity-card-desc">{act.description}</div>
                                    <div className="activity-card-meta">
                                        <span>⏱ {act.duration}</span>
                                        <span>👥 {act.participants}</span>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                );

            case "venue":
                return (
                    <div key={index} className={className}>
                        <div className="slide-title">{slide.title}</div>
                        <span className="slide-title-accent" />
                        <div className="venue-info-grid">
                            <div className="venue-info-block">
                                <div className="venue-info-label">Venue Type</div>
                                <div className="venue-info-value">{slide.content.venueType}</div>
                            </div>
                            <div className="venue-info-block">
                                <div className="venue-info-label">Capacity</div>
                                <div className="venue-info-value">{slide.content.capacity}</div>
                            </div>
                            <div className="venue-info-block" style={{ gridColumn: "1 / -1" }}>
                                <div className="venue-info-label">Setup Requirements</div>
                                <ul className="venue-setup-list">
                                    {slide.content.setup?.map((s: string, i: number) => (
                                        <li key={i}>{s}</li>
                                    ))}
                                </ul>
                            </div>
                            {slide.content.notes && (
                                <div className="venue-info-block" style={{ gridColumn: "1 / -1" }}>
                                    <div className="venue-info-label">Notes</div>
                                    <div className="venue-info-value">{slide.content.notes}</div>
                                </div>
                            )}
                        </div>
                    </div>
                );

            case "catering":
                return (
                    <div key={index} className={className}>
                        <div className="slide-title">{slide.title}</div>
                        <span className="slide-title-accent" />
                        <div className="catering-content">
                            <div className="catering-meals">
                                {slide.content.meals?.map((meal: any, i: number) => (
                                    <div key={i} className="catering-meal">
                                        <div className="catering-meal-name">{meal.name}</div>
                                        <div className="catering-meal-desc">{meal.description}</div>
                                    </div>
                                ))}
                            </div>
                            <div className="catering-sidebar">
                                <div className="catering-dietary">
                                    <div className="catering-dietary-label">Dietary Notes</div>
                                    <div>{slide.content.dietaryNotes}</div>
                                </div>
                                {slide.content.amenities?.length > 0 && (
                                    <div>
                                        <div className="catering-dietary-label">Amenities</div>
                                        <ul className="catering-amenities-list">
                                            {slide.content.amenities.map((a: string, i: number) => (
                                                <li key={i}>{a}</li>
                                            ))}
                                        </ul>
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>
                );

            case "investment":
                return (
                    <div key={index} className={className}>
                        <div className="slide-title">{slide.title}</div>
                        <span className="slide-title-accent" />
                        <div className="investment-table">
                            <div className="investment-row header">
                                <div>Category</div>
                                <div>Description</div>
                                <div>Tier</div>
                            </div>
                            {slide.content.categories?.map((cat: any, i: number) => (
                                <div key={i} className="investment-row">
                                    <div className="investment-category">{cat.category}</div>
                                    <div className="investment-desc">{cat.description}</div>
                                    <div className="investment-tier">{cat.tier}</div>
                                </div>
                            ))}
                        </div>
                        {slide.content.note && (
                            <div className="investment-note">{slide.content.note}</div>
                        )}
                    </div>
                );

            case "closing":
                return (
                    <div key={index} className={className}>
                        <div className="cover-accent-bar" />
                        <div className="slide-title" style={{ color: "inherit", textAlign: "center" }}>
                            {slide.title}
                        </div>
                        <div className="closing-message">{slide.content.message}</div>
                        <ul className="closing-values">
                            {slide.content.valueProps?.map((v: string, i: number) => (
                                <li key={i}>{v}</li>
                            ))}
                        </ul>
                        <div className="closing-contact">{slide.content.contactInfo}</div>
                    </div>
                );

            default:
                return (
                    <div key={index} className={className}>
                        <div className="slide-title">{slide.title}</div>
                        <span className="slide-title-accent" />
                        <pre style={{ whiteSpace: "pre-wrap", fontSize: "0.9rem" }}>
                            {JSON.stringify(slide.content, null, 2)}
                        </pre>
                    </div>
                );
        }
    };

    // ─── Render: Loading State ──────────────────────────────────

    if (isGenerating) {
        return (
            <div className="proposal-container">
                <div className="proposal-loading">
                    <div className="loading-slides">
                        <div className="loading-slide" />
                        <div className="loading-slide" />
                        <div className="loading-slide" />
                        <div className="loading-slide" />
                        <div className="loading-slide" />
                    </div>
                    <div className="loading-text">Crafting Your Proposal</div>
                    <div className="loading-subtext">
                        AI is analyzing {selectedCompany}'s brief and designing a custom presentation...
                    </div>
                </div>
            </div>
        );
    }

    // ─── Render: Error State ────────────────────────────────────

    if (error && !slides) {
        return (
            <div className="proposal-container">
                <div className="proposal-error">
                    <h2>Something Went Wrong</h2>
                    <p>{error}</p>
                    <button className="btn-retry" onClick={() => { setError(null); setSelectedCompany(null); fetchCompanies(); }}>
                        ← Back to Companies
                    </button>
                </div>
            </div>
        );
    }

    // ─── Render: Slide Presentation ─────────────────────────────

    if (slides) {
        return (
            <div className="proposal-container">
                <div className="proposal-viewer">
                    {/* Toolbar */}
                    <div className="viewer-toolbar">
                        <div className="viewer-toolbar-title">
                            {selectedCompany} — Proposal
                        </div>
                        <div className="viewer-toolbar-actions">
                            <button
                                className="toolbar-btn"
                                onClick={() => {
                                    setSlides(null);
                                    setCurrentSlide(0);
                                    setSelectedCompany(null);
                                    setSaveResult(null);
                                }}
                            >
                                ← Back
                            </button>
                            <button
                                className="toolbar-btn toolbar-btn-accent"
                                onClick={handleDownloadPdf}
                                disabled={isDownloading}
                            >
                                <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                                    <polyline points="7 10 12 15 17 10" />
                                    <line x1="12" y1="15" x2="12" y2="3" />
                                </svg>
                                {isDownloading ? "Downloading..." : "Download PDF"}
                            </button>
                            <button
                                className="toolbar-btn toolbar-btn-primary"
                                onClick={handleSaveToDrive}
                                disabled={isSaving || !!saveResult}
                            >
                                <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                                    {saveResult ? (
                                        <polyline points="20 6 9 17 4 12" />
                                    ) : (
                                        <>
                                            <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z" />
                                            <polyline points="14 2 14 8 20 8" />
                                        </>
                                    )}
                                </svg>
                                {isSaving ? "Saving..." : saveResult ? "Saved ✓" : "Save to Drive"}
                            </button>
                        </div>
                        {saveResult && (
                            <div className="save-success-banner">
                                Saved as <strong>{saveResult.fileName}</strong>
                                {saveResult.viewLink && (
                                    <a href={saveResult.viewLink} target="_blank" rel="noopener noreferrer">
                                        Open in Drive →
                                    </a>
                                )}
                            </div>
                        )}
                    </div>

                    {/* Slide Viewport */}
                    <div className="slide-viewport">
                        {slides.map((slide, i) => renderSlide(slide, i))}
                    </div>

                    {/* Navigation */}
                    <div className="slide-nav">
                        <button
                            className="nav-arrow"
                            onClick={goPrev}
                            disabled={currentSlide === 0}
                            aria-label="Previous slide"
                        >
                            ←
                        </button>

                        <div className="slide-dots">
                            {slides.map((_, i) => (
                                <button
                                    key={i}
                                    className={`slide-dot ${i === currentSlide ? "active" : ""}`}
                                    onClick={() => setCurrentSlide(i)}
                                    aria-label={`Go to slide ${i + 1}`}
                                />
                            ))}
                        </div>

                        <div className="slide-counter">
                            {currentSlide + 1} / {slides.length}
                        </div>

                        <button
                            className="nav-arrow"
                            onClick={goNext}
                            disabled={currentSlide === slides.length - 1}
                            aria-label="Next slide"
                        >
                            →
                        </button>
                    </div>
                </div>
            </div>
        );
    }

    // ─── Render: Company Selector ───────────────────────────────

    return (
        <div className="proposal-container">
            <div className="proposal-form-card">
                <div className="proposal-form-header">
                    <h1>Generate Proposal</h1>
                    <p>
                        Select a client to generate a professional proposal from their submitted brief and event advisor conversations.
                    </p>
                </div>

                {loadingCompanies ? (
                    <div style={{ textAlign: "center", padding: "var(--space-10)", color: "var(--text-secondary)" }}>
                        Loading companies...
                    </div>
                ) : companies.length === 0 ? (
                    <div className="company-empty-state">
                        <p style={{ fontSize: "var(--font-size-lg)", fontWeight: "var(--font-weight-bold)", marginBottom: "var(--space-2)" }}>
                            No client briefs found
                        </p>
                        <p style={{ color: "var(--text-secondary)" }}>
                            Submit a brief via the Event Intake Form first, then sync with Google Drive.
                        </p>
                    </div>
                ) : (
                    <div className="company-grid">
                        {companies.map((company) => (
                            <button
                                key={company.id}
                                className="company-card"
                                onClick={() => handleGenerate(company.name)}
                            >
                                <div className="company-card-name">{company.name}</div>
                                <div className="company-card-meta">
                                    <div className="meta-item">
                                        📄 {company.briefCount} brief{company.briefCount !== 1 ? "s" : ""} on file
                                    </div>
                                    <div className="meta-item">
                                        💬 {company.chatCount} chat{company.chatCount !== 1 ? "s" : ""} recorded
                                    </div>
                                </div>
                                <div className="company-card-action">
                                    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                                        <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
                                    </svg>
                                    Generate Proposal
                                </div>
                            </button>
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
}
