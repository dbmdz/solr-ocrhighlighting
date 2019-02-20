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


class SnippetView extends Component {
  constructor(props) {
    super(props);
    this.state = {
      renderedImage: undefined,
    }
  }

  get imageBaseUrl() {
    const { docId } = this.props;
    const { page } = this.props.snippet;
    const pageId = String(parseInt(page.split("_")[1]) - 1).padStart(4, "0");
    return `http://localhost:8080/${docId}/Image_${pageId}.JPEG`;

  }

  getHighlightStyle(hlInfo, hue) {
    const regionWidth = this.props.snippet.region.lrx - this.props.snippet.region.ulx;
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
    const { region } = this.props.snippet;
    const regionStr = `${region.ulx},${region.uly},${region.lrx - region.ulx},${region.lry - region.uly}`;
    const widthStr = width ? `${width},` : "full";
    return `${this.imageBaseUrl}/${regionStr}/${widthStr}/0/default.jpg`;
  }

  render() {
    const { text, page, highlights } = this.props.snippet;
    return (
      <div class="snippet-display">
        <hr />
        <img ref={i => this.img = i} src={this.getImageUrl()} />
        {this.state.renderedImage && highlights.flatMap(
          hls => hls.map(hl => <div class="highlight-box" style={this.getHighlightStyle(hl, 50)} />))}
        <p dangerouslySetInnerHTML={{ __html: text }} />
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
    const {docId, hl } = this.props;
    return (
      <div class="result-document">
        <Elevation z={4}>
          <Typography tag="div" headline4>{docId}</Typography>
          <Typography subtitle1>
            {hl.numTotal} matching passages found
          </Typography>
          {hl.snippets.map(snip => <SnippetView snippet={snip} docId={docId} />)}
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
    fetch(`http://localhost:8181/solr/ocrtest/select?${new URLSearchParams(params)}`)
      .then(resp => resp.json())
      .then((data) => this.setState({ searchResults: data, isSearchPending: false }))
      .catch((err) => {
        console.error(err);
        this.setState({ isSearchPending: false });
      });
    this.setState({ isSearchPending: true });
  }

  constructor(props) {
    super(props);
    this.state = {
      isSearchPending: false,
      queryParams: {
        'df': 'ocr_text',
        'hl.fl': 'ocr_text',
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
    const { searchResults, isSearchPending } = this.state;
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
                  key: idx,
                  docId: doc.id,
                  hl: searchResults.ocrHighlighting[doc.id].ocr_text }
              })
              .map(({ key, docId, hl }) => <ResultDocument key={key} hl={hl} docId={docId} />)}
        </section>
      </main>
    );
  }
}
