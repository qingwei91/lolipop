import React from 'react';

const RaftLog = props => {
  return (
    <div>
      <div>{"Command: " + props.log.cmd}</div>
      <div>{"Term: " + props.log.term}</div>
      <div>{"Idx: " + props.log.idx}</div>
    </div>
  )
};

const LogView = props => {
  return (
    <div>
      {props.logs.map(l => <RaftLog log={l}/>)}
    </div>
  )
};
const StateView = props => {
  return (<div>???</div>)
};

const RaftStatePerNode = props => {
  return (
    <div>
      <LogView logs={props.logs.slice(100)}/>
    </div>
  )
};

export const RaftStates = props => {
  /**
   * 1. split data by node id
   * 2. Per node, produce 2 views, one for state, one for logs
   * 3.
   */

  return (
    <div>
      {
        Object
          .keys(props.states)
          .map(key => {
            return <RaftStatePerNode key={key} logs={props.states[key]} />
          })
      }
    </div>
  )
};

