import './style';
import { Component } from 'preact';
import TextField from 'preact-material-components/TextField';
import LinearProgress from 'preact-material-components/LinearProgress';
import Typography from 'preact-material-components/Typography';
import Elevation from 'preact-material-components/Elevation';
import Slider from 'preact-material-components/Slider';
import FormField from 'preact-material-components/FormField';

import 'preact-material-components/TextField/style.css';
import 'preact-material-components/LinearProgress/style.css';
import 'preact-material-components/Typography/style.css';
import 'preact-material-components/Elevation/style.css';
import 'preact-material-components/Slider/style.css';
import 'preact-material-components/FormField/style.css';


var IMAGE_API_BASE = 'https://ocrhl.jbaiter.de'
if (typeof window !== 'undefined') {
  var APP_BASE = `${window.location.protocol || 'http:'}//${window.location.host}`;
} else {
  var APP_BASE = 'http://localhost:8008';  // TODO: Read from environment?
}

function highlightDocument(doc, highlights) {
  Object.keys(highlights).forEach(field => {
    doc[field] = highlightFieldValue(doc[field], highlights[field]);
  })
  return doc;
}

function highlightFieldValue(val, highlights) {
  let out = val;
  highlights.forEach((hl => {
    const rawText = hl.replace(/<\/?em>/g, '');
    out = out.split(rawText).join(hl);
  }))
  return out;
}

// TODO: Add support for snippets with regions from multiple pages
class SnippetView extends Component {
  constructor(props) {
    super(props);
    this.state = {
      renderedImage: undefined,
    }
  }

  get imageBaseUrl() {
    const { docId } = this.props;
    const page = this.props.snippet.regions[0].page;
    const pageId = String(parseInt(page.split("_")[1]) - 1).padStart(4, "0");
    return `${IMAGE_API_BASE}/iiif/image/${docId}/Image_${pageId}.JPEG`;

  }

  getHighlightStyle(hlInfo, hue) {
    const regionWidth = this.props.snippet.regions[0].lrx - this.props.snippet.regions[0].ulx;
    const scaleFactor = this.state.renderedImage.width / regionWidth;
    return {
      position: "absolute",
      left: `${(scaleFactor * hlInfo.ulx) + this.state.renderedImage.x - 2}px`,
      top: `${(scaleFactor * hlInfo.uly) + this.state.renderedImage.y - 2}px`,
      width: `${scaleFactor * (hlInfo.lrx - hlInfo.ulx)}px`,
      height: `${scaleFactor * (hlInfo.lry - hlInfo.uly)}px`,
      backgroundColor: `hsla(${hue}, 100%, 50%, 50%)`
    };
  }

  getImageUrl(width) {
    const region = this.props.snippet.regions[0];
    const regionStr = `${region.ulx},${region.uly},${region.lrx - region.ulx},${region.lry - region.uly}`;
    const widthStr = width ? `${width},` : "full";
    return `${this.imageBaseUrl}/${regionStr}/${widthStr}/0/default.jpg`;
  }

  render() {
    const { docId, query } = this.props;
    const { text, highlights } = this.props.snippet;
    const page = this.props.snippet.regions[0].page;
    const pageIdx = parseInt(page.split("_")[1]) - 1;
    const manifestUri = `${APP_BASE}/iiif/presentation/${docId}/manifest`;
    const viewerUrl = `/viewer/#?manifest=${manifestUri}&cv=${pageIdx}&q=${query}`;
    return (
      <div class="snippet-display">
        <a href={viewerUrl} target="_blank" title="Open page in viewer">
          <img ref={i => this.img = i} src={this.getImageUrl()} />
        </a>
        {this.state.renderedImage && highlights.flatMap(
          hls => hls.map(hl =>
            <div class="highlight-box" title={hl.text} style={this.getHighlightStyle(hl, 50)} />))}
        <p className="highlightable" dangerouslySetInnerHTML={{ __html: text }} />
      </div>
    );
  }

  updateDimensions() {
    if (!this.img) {
      return;
    }
    this.setState({
      renderedImage: {
        x: this.img.offsetLeft,
        y: this.img.offsetTop,
        width: this.img.width,
        height: this.img.height,
      }
    });
  }

  componentDidMount() {
    this.img.addEventListener("load", this.updateDimensions.bind(this));
    this.img.addEventListener("resize", this.updateDimensions.bind(this));
  }

  componentWillUnmount() {
    this.img.removeEventListener("resize", this.updateDimensions.bind(this));
  }
}


class ResultDocument extends Component {
  constructor(props) {
    super(props);
    this.state = {
      collapsed: true
    }
  }

  render() {
    const { hl, ocr_hl, query } = this.props;
    const doc = highlightDocument(this.props.doc, hl);
    const manifestUri = `${APP_BASE}/iiif/presentation/${doc.id}/manifest`;
    const viewerUrl = `/viewer/#?manifest=${manifestUri}&q=${query}`;
    return (
      <div class="result-document">
        <Elevation z={4}>
          <Typography tag="div" headline4>
            <a className="highlightable" href={viewerUrl} title="Open in viewer" target="_blank" dangerouslySetInnerHTML={{ __html: doc.title }} />
          </Typography>
          <Typography subtitle1>
            { ocr_hl ? ocr_hl.numTotal : 'No' } matching passages in the text found
          </Typography>
          <ul className="metadata">
            <li><strong>Created by</strong> <span className="highlightable" dangerouslySetInnerHTML={{ __html: doc.creator }} /></li>
            <li><strong>Published in</strong> {doc.date} <strong>by</strong> <span className="highlightable" dangerouslySetInnerHTML={{ __html: doc.publisher }} /></li>
            <li><strong>Language:</strong> {doc.language}</li>
          </ul>
          {ocr_hl && ocr_hl.snippets.map(snip => <SnippetView snippet={snip} docId={doc.id} query={query} />)}
        </Elevation>
      </div>);
  }
}


export default class App extends Component {
  onSubmit(evt) {
    if (evt) {
      evt.preventDefault();
    }
    const query = document.querySelector(".search-form input").value;
    const params = { ...this.state.queryParams, q: query };
    fetch(`${APP_BASE}/solr/ocrtest/select?${new URLSearchParams(params)}`)
      .then(resp => resp.json())
      .then((data) => this.setState({ searchResults: data, isSearchPending: false }))
      .catch((err) => {
        console.error(err);
        this.setState({ isSearchPending: false });
      });
    this.setState({
      isSearchPending: true,
      queryParams: params });
  }

  constructor(props) {
    super(props);
    this.state = {
      isSearchPending: false,
      queryParams: {
        'defType': 'edismax',
        'fl': 'id,title,creator,publisher,date,language',
        'qf': 'title^20.0 creator^10.0 publisher^5.0 ocr_text^0.3',
        'hl.fl': 'title,creator,publisher,ocr_text',
        'hl.snippets': 10,
        'hl.weightMatches': true,
        'hl': 'on'
      },
      searchResults: undefined
    };
  }

  onSliderChange(evt) {
    const val = evt.detail.value;
    if (typeof val === "number" && !Number.isNaN(val)) {
      this.setState({
        queryParams: {
          ...this.state.queryParams,
          'hl.snippets': val}})
      this.onSubmit();
    }
  }

  render() {
    const { searchResults, isSearchPending, queryParams } = this.state;
    return (
      <main>
        <link rel="stylesheet" href="https://fonts.googleapis.com/icon?family=Material+Icons"/>
        <form className="search-form" onSubmit={this.onSubmit.bind(this)}>
          <TextField label="Search" outlined trailingIcon="search" />
          <FormField>
            <label for="passage-slider">Number of snippets</label>
            <Slider
              discrete step={1} value={this.state.queryParams['hl.snippets']} max={50}
              onChange={this.onSliderChange.bind(this)} id="passage-slider" />
          </FormField>
          {isSearchPending &&
            <LinearProgress indeterminate />}
        </form>
        {!isSearchPending && searchResults !== undefined &&
          <Typography tag="p" subtitle1>
              Found matches in {searchResults.response.numFound} documents
              in {searchResults.responseHeader.QTime}ms.
          </Typography>}
        <section class="results">
          {searchResults !== undefined &&
            searchResults.response.docs
              .map((doc, idx) => {
                return {
                  doc,
                  key: idx,
                  ocrHl: searchResults.ocrHighlighting[doc.id].ocr_text,
                  hl: searchResults.highlighting[doc.id] }
              })
              .map(({ key, doc, hl, ocrHl }) =>
                <ResultDocument key={key} hl={hl} ocr_hl={ocrHl} doc={doc} query={queryParams.q} />)}
        </section>
      </main>
    );
  }
}
