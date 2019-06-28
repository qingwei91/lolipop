import React from 'react';
import './App.css';
import { Stage } from '@inlet/react-pixi'
import Slider from 'rc-slider';
import {RaftEvents} from "./components/RaftEvent";
import events from './data/events.json';

import { store, view } from 'react-easy-state'
import 'rc-slider/assets/index.css';

const appState = store({
  sliderV: 0,
});

const SliderTooltip = Slider.createSliderWithTooltip(Slider);

const notHeartbeat = ev => {
  if (ev.tpe.includes('AppendRPC')) {
    return ev.req.entries.length !== 0
  } else {
    return true;
  }
};

function filterHeartbeat(evs) {
  var o = {};
  for (const [k, v] of Object.entries(evs)) {
    o[k] = v.filter(notHeartbeat)
  }
  return o;
}

const filtered = events;

const App = view(() => {

  const handle = v => appState.sliderV = v;
  const opts = {
    autoResize: true,
    resolution: devicePixelRatio,
  };
  const railStyle = {
    height: 40
  };
  const trackStyle = {
    height: 35
  };
  const handleStyle = {height: 40, width: 40};

  return (
    <div className="App">
      <Stage
        options={opts}
        width={window.innerWidth}
        height={window.innerHeight * 0.9}
        raf={false}
      >
        {
          Object
            .keys(filtered)
            .map(k =>
              <RaftEvents idx={k} events={filtered[k]} sliderV={appState.sliderV / 10000} />
            )
        }
      </Stage>
      <SliderTooltip
        tipFormatter={v => v / 10000 * 100}
        min={0}
        max={10000}
        onChange={handle}
        trackStyle={[trackStyle]}
        railStyle={railStyle}
        handleStyle={handleStyle}
        style={{marginLeft: 100, marginRight: 100, width: window.innerWidth * 0.8}}
      />
    </div>
  );
});

export default App;
