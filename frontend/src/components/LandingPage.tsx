import "./LandingPage.css";
import bloomLogo from "../assets/bloom-logo.png";

interface LandingPageProps {
  onGetStarted: () => void;
  theme: "light" | "dark";
  toggleTheme: () => void;
}

interface Feature {
  kicker: string;
  title: string;
  description: string;
  icon: React.ReactNode;
}

const STATS: { k: string; v: string }[] = [
  { k: "Deck", v: "9 slides" },
  { k: "Export", v: "16:9 PDF" },
  { k: "Answers", v: "Source-cited" },
  { k: "Drive", v: "Two-way sync" },
];

const FEATURES: Feature[] = [
  {
    kicker: "Advisor",
    title: "Event Advisor",
    description:
      "Ask anything about a client or past event. Bloom answers from your own documents and always shows its sources.",
    icon: (
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
      </svg>
    ),
  },
  {
    kicker: "Intake",
    title: "Event Intake Form",
    description:
      "One simple form covers culture, audience, venue, and catering — then files itself as a PDF in the client's Drive folder.",
    icon: (
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"></path>
        <rect x="8" y="2" width="8" height="4"></rect>
      </svg>
    ),
  },
  {
    kicker: "Proposals",
    title: "AI Proposal Generator",
    description:
      "One click turns a brief into a polished nine-slide proposal — ready to view in the browser or download as a PDF.",
    icon: (
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect>
        <line x1="8" y1="21" x2="16" y2="21"></line>
        <line x1="12" y1="17" x2="12" y2="21"></line>
      </svg>
    ),
  },
  {
    kicker: "Sync",
    title: "Google Drive Sync",
    description:
      "Works both ways: your briefs and proposals upload to Drive, and any PDF you drop in gets read and added to the knowledge base automatically.",
    icon: (
      <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"></path>
      </svg>
    ),
  },
];

const STEPS = [
  {
    n: "01",
    title: "Capture the brief",
    description:
      "Fill in the Event Intake form or drop client PDFs into Drive. Bloom reads and indexes everything for you.",
  },
  {
    n: "02",
    title: "Explore with the Advisor",
    description:
      "Ask about the client, past events, or ideas — every answer comes straight from your own documents, with citations.",
  },
  {
    n: "03",
    title: "Generate the proposal",
    description:
      "Get a client-ready proposal in one click. Download it as a PDF or save it straight to the client's Drive folder.",
  },
];

const ArrowRight = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.25" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <line x1="4" y1="12" x2="19" y2="12"></line>
    <polyline points="12 5 19 12 12 19"></polyline>
  </svg>
);

export default function LandingPage({ onGetStarted }: LandingPageProps) {
  return (
    <div className="landing" id="top">
      <header className="landing-topbar">
        <div className="landing-topbar-inner">
          <a href="#top" className="landing-brand" aria-label="Bloom home">
            <img src={bloomLogo} alt="" className="landing-logo-mark" />
            <span className="landing-wordmark">
              Bloom<span className="landing-wordmark-dot">.</span>
            </span>
          </a>
          <nav className="landing-nav" aria-label="Section navigation">
            <a href="#features">Features</a>
            <a href="#run">How it works</a>
          </nav>
          <button type="button" className="btn-launch" onClick={onGetStarted}>
            Launch app <ArrowRight />
          </button>
        </div>
      </header>

      <main className="landing-main">
        {/* HERO — the cover of the brief */}
        <section className="landing-hero">
          <div className="section-inner hero-inner">
            <div className="hero-copy">
              <div className="hero-logo-badge" aria-hidden="true">
                <img src={bloomLogo} alt="" className="hero-logo-badge-img" />
              </div>
              <p className="doc-label">Built for event teams</p>
              <h1 className="hero-title">
                From a blank brief to a proposal your client can present.
              </h1>
              <p className="hero-lede">
                Drop in a client brief or a folder of old decks. Bloom keeps it all organized and
                searchable, then drafts the proposal for you — sources cited, files saved back to Drive.
              </p>

              <div className="hero-actions">
                <button type="button" className="btn-primary" onClick={onGetStarted}>
                  Start a brief <ArrowRight />
                </button>
                <a className="btn-quiet" href="#run">See how it works</a>
              </div>

              <dl className="stat-strip">
                {STATS.map((s) => (
                  <div className="stat" key={s.k}>
                    <dt>{s.k}</dt>
                    <dd>{s.v}</dd>
                  </div>
                ))}
              </dl>
            </div>
          </div>
        </section>

        {/* CAPABILITIES — the kit */}
        <section className="landing-section" id="features">
          <div className="section-inner">
            <div className="section-head">
              <p className="doc-label">Capabilities</p>
              <h2 className="section-title">Four tools, one workflow.</h2>
            </div>

            <div className="cap-grid">
              {FEATURES.map((f) => (
                <article className="cap" key={f.title}>
                  <div className="cap-top">
                    <span className="cap-kicker">{f.kicker}</span>
                    <span className="cap-icon">{f.icon}</span>
                  </div>
                  <h3 className="cap-title">{f.title}</h3>
                  <p className="cap-desc">{f.description}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        {/* RUN OF SHOW — the process is a genuine sequence */}
        <section className="landing-section" id="run">
          <div className="section-inner">
            <div className="section-head">
              <p className="doc-label">Run of show</p>
              <h2 className="section-title">Three moves, blank page to deck.</h2>
            </div>

            <ol className="run">
              {STEPS.map((s) => (
                <li className="run-item" key={s.n}>
                  <div className="run-marker" aria-hidden="true">
                    <span className="run-num">{s.n}</span>
                  </div>
                  <div className="run-body">
                    <h3 className="run-title">{s.title}</h3>
                    <p className="run-desc">{s.description}</p>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        </section>
      </main>

      {/* CTA — house lights down */}
      <section className="landing-cta">
        <div className="section-inner cta-inner">
          <p className="doc-label doc-label-invert">Ready when you are</p>
          <h2 className="cta-title">Open a brief and let Bloom take it to a deck.</h2>
          <button type="button" className="btn-primary btn-primary-lg" onClick={onGetStarted}>
            Start a brief <ArrowRight />
          </button>
        </div>
      </section>

      <footer className="landing-footer">
        <div className="section-inner footer-inner">
          <span className="landing-brand landing-brand-sm">
            <img src={bloomLogo} alt="" className="landing-logo-mark" />
            <span className="landing-wordmark landing-wordmark-sm">
              Bloom<span className="landing-wordmark-dot">.</span>
            </span>
          </span>
          <span className="footer-note">AI event planning platform</span>
        </div>
      </footer>
    </div>
  );
}
