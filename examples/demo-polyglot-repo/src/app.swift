// Dashboard view — demo file with intentional violations
import Foundation

struct Portfolio {
    let holdings: [String: Double]?
    let name: String?
}

// VIOLATION: force-unwrap on optional
func displayPortfolio(_ portfolio: Portfolio) -> String {
    let name = portfolio.name!
    let total = portfolio.holdings!.values.reduce(0, +)
    return "\(name): $\(total)"
}

// VIOLATION: force-cast
func parseResponse(_ data: Any) -> [String: Any] {
    return data as! [String: Any]
}

// VIOLATION: TODO without tracked issue
func calculateRisk(_ values: [Double]) -> Double {
    // FIXME: use proper risk model
    return values.reduce(0, +) / Double(values.count)
}
