import React from 'react';
import './App.css';
import { Stage } from '@inlet/react-pixi'
import Slider, { Range } from 'rc-slider';
import {RaftEvents} from "./components/RaftEvent";
import events from './data/events.json';

import { store, view } from 'react-easy-state'
import 'rc-slider/assets/index.css';

const appState = store({
  sliderV: 0,
});

const App = view(() => {
  const events0 = events['0'];

  const handle = v => appState.sliderV = v;

  return (
    <div className="App">
      <Stage>
        <RaftEvents events={events0} sliderV={appState.sliderV / 10000} />
      </Stage>
      <Slider min={0} max={10000} onChange={handle}/>
    </div>
  );
});

export default App;
