import React from 'react';
import './App.css';
import { Stage } from '@inlet/react-pixi'
import Slider from 'rc-slider';
import {RaftEvents} from "./components/RaftEvent";
import {RaftStates} from "./components/RaftState";
import eventsData from './data/events.json';
import {eventsToListen, extractData} from './data/events';

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

const scan = arr => i => fn => {
  const result = [];
  arr.forEach(function (a, idx) {
    if (idx === 0) {
      result.push(fn(i)(a));
    } else {
      result.push(fn(result[idx - 1])(a));
    }
  });

  return result;
};

let filtered = {};

Object.keys(eventsData).forEach(k => {
  let f = eventsData[k]
    .filter(e => {
      return eventsToListen.includes(e.tpe);
    });

  filtered[k] = scan(f)({})(extractData);
});

const App = view(() => {

  return (
    <div className="App">
      <RaftStates states={filtered}/>
    </div>
  );
});

export default App;
