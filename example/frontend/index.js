import "preact/debug";
import "./style";
import ResizeObserver from "resize-observer-polyfill";
import { Component } from "preact";
import { useState, useRef, useEffect, useMemo } from "preact/hooks";
import TextField from "preact-material-components/TextField";
import LinearProgress from "preact-material-components/LinearProgress";
import Typography from "preact-material-components/Typography";
import Elevation from "preact-material-components/Elevation";
import Slider from "preact-material-components/Slider";
import FormField from "preact-material-components/FormField";
import Checkbox from "preact-material-components/Checkbox";

import "preact-material-components/TextField/style.css";
import "preact-material-components/LinearProgress/style.css";
import "preact-material-components/Typography/style.css";
import "preact-material-components/Elevation/style.css";
import "preact-material-components/Slider/style.css";
import "preact-material-components/FormField/style.css";
import "preact-material-components/Checkbox/style.css";

var PARAMS = {
  fl:
    "id,source,issue_id,title,subtitle,newspaper_part,author,publisher,date,language",
  qf:
    "title^20.0 subtitle^16.0 author^10.0 newspaper_part^5.0 publisher^5.0 ocr_text^0.3",
  "hl.fl": "title,subtitle,author,publisher",
  "hl.ocr.fl": "ocr_text",
};
var BNL_10MM_TO_PIX_FACTOR = 300 / 254;
var IMAGE_API_BASE = "https://ocrhl.jbaiter.de/iiif/image/v2";
if (typeof window !== "undefined") {
  var APP_BASE = `${window.location.protocol || "http:"}//${
    window.location.host
  }`;
} else {
  var APP_BASE = "http://localhost:8181"; // TODO: Read from environment?
}

// Largely a 1:1 port of https://github.com/ZeeCoder/use-resize-observer (MIT-licensed) to Preact
function useResizeObserver({ ref, onResize }) {
  const defaultRef = useRef(null);

  if (!ref) {
    ref = defaultRef;
  }
  const [size, setSize] = useState({ width: undefined, height: undefined });
  const previous = useRef({ width: undefined, height: undefined });

  useEffect(() => {
    if (
      typeof ref !== "object" ||
      ref === null ||
      !(ref.current instanceof Element)
    ) {
      return;
    }

    const element = ref.current;
    const observer = new ResizeObserver((entries) => {
      if (!Array.isArray(entries) || !entries.length) {
        return;
      }

      const entry = entries[0];
      const newWidth = Math.round(entry.contentRect.width);
      const newHeight = Math.round(entry.contentRect.height);
      if (
        previous.current.width !== newWidth ||
        previous.current.height !== newHeight
      ) {
        const newSize = { width: newWidth, height: newHeight };
        if (onResize) {
          onResize(newSize);
        } else {
          previous.current.width = newWidth;
          previous.current.height = newHeight;
          setSize(newSize);
        }
      }
    });
    observer.observe(element);
    return () => observer.unobserve(element);
  }, [ref, onResize]);

  return useMemo(() => ({ ref, width: size.width, height: size.height }), [
    ref,
    size ? size.width : null,
    size ? size.height : null,
  ]);
}

function highlightDocument(doc, highlights) {
  Object.keys(highlights).forEach((field) => {
    if (Array.isArray(doc[field])) {
      doc[field] = doc[field].map((fval) =>
        highlightFieldValue(fval, highlights[field])
      );
    } else {
      doc[field] = highlightFieldValue(doc[field], highlights[field]);
    }
  });
  return doc;
}

function highlightFieldValue(val, highlights) {
  let out = val;
  highlights.forEach((hl) => {
    const rawText = hl.replace(/<\/?em>/g, "");
    if (out.indexOf(rawText) > -1) {
      out = out.split(rawText).join(hl);
    }
  });
  return out;
}

const HighlightDisplay = ({ scaleFactor, highlight }) => {
  const left = scaleFactor * highlight.ulx;
  const top = scaleFactor * highlight.uly;
  const width = scaleFactor * (highlight.lrx - highlight.ulx);
  const height = scaleFactor * (highlight.lry - highlight.uly);

  const style = {
    position: "absolute",
    left: `${left}px`,
    top: `${top}px`,
    width: `${width}px`,
    height: `${height}px`,
    backgroundColor: `hsla(50, 100%, 50%, 50%)`,
  };
  return <div style={style} title={highlight.text} />;
};

const RegionDisplay = ({
  manifestUri,
  page,
  region,
  highlights,
  query,
  getImageUrl,
}) => {
  const [scaleFactor, setScaleFactor] = useState(undefined);
  const { ref } = useResizeObserver({
    onResize: ({ width }) => setScaleFactor(width / (region.lrx - region.ulx)),
  });

  const viewerUrl = `/viewer/?manifest=${manifestUri}&cv=${page.id}&q=${query}`;
  return (
    <div class="region-display">
      <div class="region-img-container">
        <a href={viewerUrl} target="_blank" title="Open page in viewer">
          <img ref={ref} alt={region.text} src={getImageUrl(region, page)} />
        </a>
        {scaleFactor &&
          highlights.map((hl) => (
            <HighlightDisplay
              scaleFactor={scaleFactor}
              highlight={hl}
              key={`${hl.ulx}.${hl.uly}`}
            />
          ))}
      </div>
      <p class="highlightable" dangerouslySetInnerHTML={{ __html: region.text }} />
    </div>
  );
};

const SnippetDisplay = ({
  snippet,
  docId,
  manifestUri,
  query,
  getImageUrl,
}) => {
  return (
    <div class="snippet-display">
      {snippet.regions.map((region, idx) => {
        const page = snippet.pages[region.pageIdx];
        const highlights = snippet.highlights
          .flatMap((hl) => hl)
          .filter((hl) => hl.parentRegionIdx === idx);
        return (
          <RegionDisplay
            key={`region-${docId}-${idx}`}
            getImageUrl={getImageUrl}
            manifestUri={manifestUri}
            page={page}
            region={region}
            highlights={highlights}
            query={query}
          />
        );
      })}
    </div>
  );
};

class NewspaperResultDocument extends Component {
  getImageUrl(region, page, width) {
    const issueId = this.props.doc.issue_id;
    const pageNo = page.id.substring(1).padStart(5, "0");
    const x = parseInt(BNL_10MM_TO_PIX_FACTOR * region.ulx);
    const y = parseInt(BNL_10MM_TO_PIX_FACTOR * region.uly);
    const w = parseInt(BNL_10MM_TO_PIX_FACTOR * (region.lrx - region.ulx));
    const h = parseInt(BNL_10MM_TO_PIX_FACTOR * (region.lry - region.uly));
    const regionStr = `${x},${y},${w},${h}`;
    const widthStr = width ? `${width},` : "full";
    return `${IMAGE_API_BASE}/bnl:${issueId}_${pageNo}/${regionStr}/${widthStr}/0/default.jpg`;
  }

  render() {
    const { hl, ocr_hl, query } = this.props;
    const doc = highlightDocument(this.props.doc, hl);
    const manifestUri = `${APP_BASE}/iiif/presentation/bnl:${doc.issue_id}/manifest`;
    const pageIdx =
      parseInt(
        ocr_hl.snippets[0].pages[
          ocr_hl.snippets[0].regions[0].pageIdx
        ].id.substring(1)
      ) - 1;
    const viewerUrl = `/viewer/?manifest=${manifestUri}&cv=${pageIdx}&q=${query}`;
    return (
      <div class="result-document">
        <Elevation z={4}>
          <Typography tag="div" headline4>
            <a
              className="highlightable"
              href={viewerUrl}
              title="Open in viewer"
              target="_blank"
              dangerouslySetInnerHTML={{ __html: doc.title }}
            />
          </Typography>
          {doc.subtitle && (
            <Typography tag="div" subtitle1>
              {doc.subtitle.map((t) => (
                <a
                  className="highlightable"
                  href={viewerUrl}
                  title="Open in viewer"
                  target="_blank"
                  dangerouslySetInnerHTML={{ __html: t }}
                />
              ))}
            </Typography>
          )}
          <Typography subtitle1>
            {ocr_hl ? ocr_hl.numTotal : "No"} matching passages in the article
            found
          </Typography>
          <ul className="metadata">
            {/* TODO: Creators */}
            <li>
              <strong>Issue</strong> {doc.newspaper_part}
            </li>
            <li>
              <strong>Published </strong> {doc.date}
            </li>
          </ul>
          {ocr_hl &&
            ocr_hl.snippets.map((snip) => (
              <SnippetDisplay
                snippet={snip}
                docId={doc.issue_id}
                query={query}
                manifestUri={manifestUri}
                getImageUrl={this.getImageUrl.bind(this)}
              />
            ))}
        </Elevation>
      </div>
    );
  }
}

class GoogleResultDocument extends Component {
  getImageUrl(region, page, width) {
    const volId = this.props.doc.id;
    const pageId = String(parseInt(page.id.split("_")[1]) - 1).padStart(4, "0");
    const regionStr = `${region.ulx},${region.uly},${region.lrx - region.ulx},${
      region.lry - region.uly
    }`;
    const widthStr = width ? `${width},` : "full";
    return `${IMAGE_API_BASE}/gbooks:${volId}_${pageId}/${regionStr}/${widthStr}/0/default.jpg`;
  }

  render() {
    const { hl, ocr_hl, query } = this.props;
    const doc = highlightDocument(this.props.doc, hl);
    const manifestUri = `${APP_BASE}/iiif/presentation/gbooks:${doc.id}/manifest`;
    const viewerUrl = `/viewer/?manifest=${manifestUri}&q=${query}`;
    return (
      <div class="result-document">
        <Elevation z={4}>
          <Typography tag="div" headline4>
            <a
              className="highlightable"
              href={viewerUrl}
              title="Open in viewer"
              target="_blank"
              dangerouslySetInnerHTML={{ __html: doc.title }}
            />
          </Typography>
          <Typography subtitle1>
            {ocr_hl ? ocr_hl.numTotal : "No"} matching passages in the text
            found
          </Typography>
          <ul className="metadata">
            {doc.author && (
              <li>
                <strong>Created by</strong>{" "}
                <span
                  className="highlightable"
                  dangerouslySetInnerHTML={{ __html: doc.author[0] }}
                />
              </li>
            )}
            {doc.publisher && (
              <li>
                <strong>Published in</strong> {doc.date.split("-")[0]}{" "}
                <strong>by</strong>{" "}
                <span
                  className="highlightable"
                  dangerouslySetInnerHTML={{ __html: doc.publisher }}
                />
              </li>
            )}
            <li>
              <strong>Language:</strong> {doc.language}
            </li>
          </ul>
          {ocr_hl &&
            ocr_hl.snippets.map((snip) => (
              <SnippetDisplay
                snippet={snip}
                docId={doc.id}
                query={query}
                manifestUri={manifestUri}
                getImageUrl={this.getImageUrl.bind(this)}
              />
            ))}
        </Elevation>
      </div>
    );
  }
}

export default class App extends Component {
  constructor(props) {
    super(props);
    this.state = {
      isSearchPending: false,
      queryParams: {
        defType: "edismax",
        "hl.snippets": 10,
        "hl.weightMatches": true,
        hl: "on",
      },
      sources: ["gbooks", "lunion"],
      searchResults: undefined,
    };
  }

  onSubmit(evt) {
    if (evt) {
      evt.preventDefault();
    }
    const query = document.querySelector(".search-form input").value;
    const params = {
      ...this.state.queryParams,
      ...PARAMS,
      q: query,
    };
    if (this.state.sources.length == 1) {
      params.fq = "source:" + this.state.sources[0];
    }
    fetch(`${APP_BASE}/solr/ocr/select?${new URLSearchParams(params)}`)
      .then((resp) => resp.json())
      .then((data) =>
        this.setState({ searchResults: data, isSearchPending: false })
      )
      .catch((err) => {
        console.error(err);
        this.setState({ isSearchPending: false });
      });
    this.setState({
      isSearchPending: true,
      queryParams: params,
    });
  }

  onSliderChange(evt) {
    const val = evt.detail.value;
    if (typeof val === "number" && !Number.isNaN(val)) {
      this.setState({
        queryParams: {
          ...this.state.queryParams,
          "hl.snippets": val,
        },
      }, () => this.onSubmit());
    }
  }

  onSourceToggle(source, enabled) {
    let { sources } = this.state;
    if (enabled) {
      sources.push(source);
    } else {
      sources = sources.filter((s) => s !== source);
    }
    this.setState({
      sources,
    });
  }

  render() {
    const { searchResults, isSearchPending, queryParams, sources } = this.state;
    return (
      <main>
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/icon?family=Material+Icons"
        />
        <form className="search-form" onSubmit={this.onSubmit.bind(this)}>
          <TextField
            disabled={isSearchPending || sources.length === 0}
            label="Search"
            outlined
            trailingIcon="search"
          />
          <div className="source-picker">
            <FormField>
              <Checkbox
                id="search-gbooks"
                checked={sources.indexOf("gbooks") > -1}
                onChange={(evt) =>
                  this.onSourceToggle("gbooks", evt.target.checked)
                }
              />
              <label for="search-gbooks">Google Books 1000</label>
            </FormField>
            <FormField>
              <Checkbox
                id="search-bnl"
                checked={sources.indexOf("lunion") > -1}
                onChange={(evt) =>
                  this.onSourceToggle("lunion", evt.target.checked)
                }
              />
              <label for="search-bnl">L'Union Newspaper</label>
            </FormField>
          </div>
          <FormField className="passage-slider">
            <label for="passage-slider">Number of snippets</label>
            <Slider
              discrete
              step={1}
              value={this.state.queryParams["hl.snippets"]}
              max={50}
              onChange={this.onSliderChange.bind(this)}
              id="passage-slider"
              disabled={isSearchPending}
            />
          </FormField>
          {isSearchPending && <LinearProgress indeterminate />}
        </form>
        {!isSearchPending && searchResults !== undefined && (
          <Typography tag="p" subtitle1>
            Found matches in {searchResults.response.numFound} documents in{" "}
            {searchResults.responseHeader.QTime}ms.
          </Typography>
        )}
        <section class="results">
          {searchResults !== undefined &&
            searchResults.response.docs
              .map((doc, idx) => {
                return {
                  doc,
                  key: idx,
                  ocrHl: searchResults.ocrHighlighting[doc.id].ocr_text,
                  hl: searchResults.highlighting[doc.id],
                };
              })
              .map(({ key, doc, hl, ocrHl }) =>
                doc.source === "gbooks" ? (
                  <GoogleResultDocument
                    key={key}
                    hl={hl}
                    ocr_hl={ocrHl}
                    doc={doc}
                    query={queryParams.q}
                  />
                ) : (
                  <NewspaperResultDocument
                    key={key}
                    hl={hl}
                    ocr_hl={ocrHl}
                    doc={doc}
                    query={queryParams.q}
                  />
                )
              )}
        </section>
      </main>
    );
  }
}
