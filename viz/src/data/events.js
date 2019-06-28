const eventsToListen = [
  'ReceivedClientCmd',
  'VoteRPCReplied',
  'VoteRPCEnded',
  'Elected',
  'AppendRPCReplied',
  'AppendRPCEnded',
  'LogCommitted',
];

/**
 *
 * Event data schema
 * {
 *   nodeId: ???
 *   receivedCmd: [cmd...],
 *   repliedCmd: [cmd...],
 *   logs: [...],
 *   votes: [id ...],
 *   voted: ???,
 *   term: Int,
 *   commitIdx: X
 *   lastApplied: X
 *   state: X
 *   serverTpe: X
 * }
 */

const serverTpes = {
  FOLLOWER: 'Follower',
  LEADER: 'Leader',
  CANDIDATE: 'Candidate'
};

const extractData = ev => {
  data => {
    const newD = Object.assign({}, data);
    switch (ev.tpe) {
      case 'ReceivedClientCmd':
        newD.receivedCmd.push(ev.cmd);
        break;

      case 'VoteRPCStarted':
        newD.serverTpe = serverTpes.CANDIDATE;
        break;

      case 'VoteRPCReplied':
        const voted = ev.res.voteGranted;
        if (voted) {
          newD.voted = ev.req.candidateID;
          newD.term = ev.res.term;
        }
        break;
      case 'VoteRPCEnded':
        const votedBy = ev.res.voteGranted;
        if (votedBy) {
          newD.votes.push(ev.remoteId);
        }
        break;
      case 'Elected':
        newD.serverTpe = serverTpes.LEADER;
        newD.term = ev.term;
        break;
      case 'AppendRPCReplied':
        if (ev.res.success) {
          newD.term = ev.res.term;
          newD.logs.concat(ev.req.entries);
        }
        break;
      case 'AppendRPCEnded':
        break;
      case 'LogCommitted':
        newD.commitIdx = ev.idx;
        newD.state = ev.state;
        break;
    }
  }
};
