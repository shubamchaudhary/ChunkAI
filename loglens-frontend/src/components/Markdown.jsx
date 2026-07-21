import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

/**
 * Shared markdown renderer for the report and grounded chat answers. GitHub-
 * flavoured (tables, lists, code) with Tailwind-styled elements so output reads
 * like a real document instead of a raw ``# heading`` dump. No raw HTML is ever
 * rendered (react-markdown escapes it), so model output can't inject markup.
 */
const components = {
  h1: (p) => <h1 className="text-xl font-bold text-gray-900 mt-2 mb-3" {...p} />,
  h2: (p) => <h2 className="text-base font-semibold text-gray-900 mt-5 mb-2 pb-1 border-b border-gray-100" {...p} />,
  h3: (p) => <h3 className="text-sm font-semibold text-gray-800 mt-4 mb-1.5" {...p} />,
  p: (p) => <p className="text-sm leading-relaxed text-gray-700 my-2" {...p} />,
  ul: (p) => <ul className="list-disc pl-5 my-2 space-y-1 text-sm text-gray-700" {...p} />,
  ol: (p) => <ol className="list-decimal pl-5 my-2 space-y-1 text-sm text-gray-700" {...p} />,
  li: (p) => <li className="leading-relaxed" {...p} />,
  strong: (p) => <strong className="font-semibold text-gray-900" {...p} />,
  a: (p) => <a className="text-indigo-600 underline" target="_blank" rel="noreferrer" {...p} />,
  blockquote: (p) => (
    <blockquote className="border-l-2 border-gray-200 pl-3 my-2 text-sm text-gray-500 italic" {...p} />
  ),
  code: ({ inline, ...p }) =>
    inline ? (
      <code className="bg-gray-100 text-gray-800 rounded px-1 py-0.5 text-[12px] font-mono" {...p} />
    ) : (
      <code className="block bg-gray-900 text-gray-100 rounded-md p-3 my-2 text-[12px] font-mono overflow-x-auto" {...p} />
    ),
  table: (p) => (
    <div className="my-3 overflow-x-auto">
      <table className="min-w-full text-sm border border-gray-200 rounded-lg" {...p} />
    </div>
  ),
  thead: (p) => <thead className="bg-gray-50" {...p} />,
  th: (p) => <th className="text-left font-semibold text-gray-700 px-3 py-2 border-b border-gray-200" {...p} />,
  td: (p) => <td className="px-3 py-2 border-b border-gray-100 text-gray-700 align-top" {...p} />,
  hr: () => <hr className="my-4 border-gray-100" />,
};

export default function Markdown({ children }) {
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
      {children || ''}
    </ReactMarkdown>
  );
}
