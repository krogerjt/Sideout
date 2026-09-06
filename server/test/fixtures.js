export const board = {
  games: [{ game: {
    gameID: '6625873', gameState: 'live', currentPeriod: '5TH SET', startTimeEpoch: '1788634800',
    away: { score: '2', names: { short: 'Idaho St.', char6: 'ID ST', seo: 'idaho-st' }, rank: '' },
    home: { score: '2', names: { short: 'Boise St.', char6: 'BOISE', seo: 'boise-st' }, rank: '' }
  } }]
};
// Reduced from the NCAA response observed on 2026-09-05, not generated live data.
export const detail = {
  contests: [{ id: '6625873', statusCodeDisplay: 'live', currentPeriod: '5TH SET',
    teams: [{ isHome: true, score: 2, color: '#0032A0' }, { isHome: false, score: 2, color: '#F47920' }],
    linescores: [
      { period: '1', home: '25', visit: '18' }, { period: '2', home: '22', visit: '25' },
      { period: '3', home: '21', visit: '25' }, { period: '4', home: '25', visit: '23' },
      { period: '5', home: '1', visit: '1' }
    ], location: { venue: 'ExtraMile Arena' }
  }]
};
