import type { Problem } from '../api/problem';

export function ProblemAlert({ problem }: { problem: Problem }) {
  return (
    <div className="problem" role="alert">
      <h3>{problem.title}</h3>
      {problem.detail && <p>{problem.detail}</p>}
      {problem.errors && problem.errors.length > 0 && (
        <ul>
          {problem.errors.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      )}
      {problem.traceId && (
        <p>
          Trace id: <code>{problem.traceId}</code>
        </p>
      )}
    </div>
  );
}
