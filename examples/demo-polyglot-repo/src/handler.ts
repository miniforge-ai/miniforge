// API handler — demo file with intentional violations

interface Portfolio {
  id: string;
  name: string;
  holdings: Record<string, number>;
}

// VIOLATION: console.log in production code
export function handleRequest(req: any): Portfolio {
  console.log("Processing request:", req.id);

  const portfolio: Portfolio = {
    id: req.id,
    name: req.name || "Unknown",
    holdings: req.data,
  };

  // VIOLATION: TODO without tracked issue
  // TODO: add input validation
  return portfolio;
}

// VIOLATION: any type
export function processData(data: any): any {
  return JSON.parse(JSON.stringify(data));
}
